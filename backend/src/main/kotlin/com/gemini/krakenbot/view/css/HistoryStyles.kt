package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HistoryRangeCss
import com.gemini.krakenbot.view.util.HistoryRangeIds
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
import kotlinx.css.marginTop
import kotlinx.css.opacity
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
        (
            "#${HistoryRangeIds.PANEL_PORTFOLIO}[hidden], #${HistoryRangeIds.PANEL_ACTIVITY}[hidden], " +
                "#${HistoryRangeIds.PANEL_DATA_STATUS}[hidden], #${HistoryRangeIds.ACTIVITY_BOT_PANEL}[hidden], " +
                "#${HtmlIds.HISTORY_TRADES_SECTION}[hidden], #${HtmlIds.HISTORY_LEDGERS_SECTION}[hidden]"
            ) { display = Display.none }
        "#history-range-custom-panel[hidden], #history-scope-link[hidden], #history-coverage-sparse[hidden]" {
            display = Display.none
        }
        "#${HtmlIds.HISTORY_SAVED_VIEWS}" {
            alignItems = Align.end
            padding = Padding(0.rem)
            width = 100.pct
            put("margin", "0")
        }
        (
            ".${CssClass.History.ChartControls} .active, " +
                "${CssClass.History.TimeRangeBtnActive.querySelector}"
            ) {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorBlueGlassBgHover.value
            solidBorder(CssTheme.colorBlueAccent)
            boxShadowRaw(CssTheme.insetTopHighlight)
        }
        ".${CssClass.History.GridLine}" {
            strokeRaw(ChartProps.COLOR_GRID_LINE)
            strokeWidthRaw("1")
        }
        ".${CssClass.History.StatsGrid}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("repeat(2, minmax(0, 1fr))")
            gap = 0.75.rem
            marginBottom = 1.rem
            alignItems = Align.start
        }
        ".${CssClass.History.ReportDetails} summary" {
            put("cursor", "pointer")
            fontSize = 0.8125.rem
            color = CssTheme.colorTextSecondary
            padding = Padding(0.375.rem, 0.rem)
        }
        ".${CssClass.History.ReportDetails} dl" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("minmax(10rem, 14rem) minmax(0, 1fr)")
            gap = 0.5.rem
            put("column-gap", "1rem")
            put("margin", "0.625rem 0 0")
        }
        ".${CssClass.History.ReportDetails} dt, .${CssClass.History.ReportDetails} dd" {
            put("padding-bottom", "0.375rem")
            put("border-bottom", "1px solid ${CssTheme.colorBorderSubtle.value}")
        }
        ".${CssClass.History.ReportDetails} dt" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
        }
        ".${CssClass.History.ReportDetails} dd" {
            put("margin", "0")
            color = CssTheme.colorTextPrimary
            fontSize = 0.8125.rem
            put("font-variant-numeric", "tabular-nums")
            overflowWrapRaw("anywhere")
            textAlign = TextAlign.right
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
            transitionRaw("all 0.2s ease")
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
        ".${CssClass.History.ChartSvg} circle.${CssClass.Utility.Active}" {
            strokeRaw(ChartProps.COLOR_TICK)
            strokeWidthRaw("2")
        }
        CssClass.History.GapMarker.querySelector {
            fillRaw(CssTheme.colorWarning.value)
        }
        CssClass.History.AxisLabel.querySelector {
            fillRaw(ChartProps.COLOR_TICK)
            fontSize = 0.6875.rem
            fontFamily = CssTheme.fontMono
        }
        ".${CssClass.History.ChartScrubber}" {
            padding = Padding(0.5.rem, 0.25.rem, 0.125.rem)
        }
        ".${CssClass.History.ChartScrubberInput}" {
            appearanceRaw("none")
            webkitAppearanceRaw("none")
            width = 100.pct
            height = 0.35.rem
            borderRadius = CssTheme.radiusPill
            background = CssTheme.colorBorderMuted.value
            accentColorRaw(CssTheme.colorBlueAccent.value)
        }
        ".${CssClass.History.ChartScrubberInput}:disabled" {
            opacity = 0.3
        }
        ".${CssClass.History.ChartScrubberInput}::-webkit-slider-thumb" {
            appearanceRaw("none")
            webkitAppearanceRaw("none")
            width = 1.rem
            height = 1.rem
            borderRadius = 50.pct
            background = CssTheme.colorBlueAccent.value
        }
        ".${CssClass.History.ChartScrubberInput}::-moz-range-thumb" {
            width = 1.rem
            height = 1.rem
            borderRadius = 50.pct
            background = CssTheme.colorBlueAccent.value
        }
        ".${CssClass.History.ChartLegend}" {
            display = Display.flex
            flexWrapRaw("wrap")
            gap = 0.75.rem
            padding = Padding(0.5.rem, 0.25.rem)
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
        }
        ".${CssClass.History.ChartLegendItem}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.375.rem
        }
        ".${CssClass.History.ChartLegendSwatch}" {
            width = 0.625.rem
            height = 0.625.rem
            borderRadius = CssTheme.radiusSm
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
            transitionRaw("all 0.2s ease")
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
        ".${HistoryRangeCss.CHIP}" {
            display = Display.inlineBlock
            padding = Padding(0.25.rem, 0.625.rem)
            borderRadius = CssTheme.radiusPill
            fontSize = 0.6875.rem
            fontFamily = CssTheme.fontSans
        }
        ".${HistoryRangeCss.CHIP_OK}" {
            color = CssTheme.colorSuccess
            background = CssTheme.colorSuccessMuted.value
            solidBorder(CssTheme.colorSuccessBorder)
        }
        ".${HistoryRangeCss.CHIP_BUSY}" {
            color = CssTheme.colorBlueAccent
            background = CssTheme.colorBlueGlassBg.value
            solidBorder(CssTheme.colorBlueGlassBorder)
        }
        ".${HistoryRangeCss.CHIP_WARN}" {
            color = CssTheme.colorWarning
            background = CssTheme.colorWarningMuted.value
            solidBorder(CssTheme.colorWarningBorder)
        }
        ".${HistoryRangeCss.CHIP_ERROR}" {
            color = CssTheme.colorDanger
            background = CssTheme.colorDangerMuted.value
            solidBorder(CssTheme.colorDangerBorder)
        }
        ".${HistoryRangeCss.BANNER}" {
            display = Display.flex
            alignItems = Align.center
            justifyContentRaw("space-between")
            flexWrapRaw("wrap")
            gap = 0.75.rem
            padding = Padding(0.625.rem, 0.75.rem)
            marginBottom = 0.75.rem
            borderRadius = CssTheme.radiusMd
            background = CssTheme.colorWarningMuted.value
            color = CssTheme.colorWarning
        }
        ".${HistoryRangeCss.BANNER}[hidden]" { display = Display.none }
        ".${HistoryRangeCss.BANNER} p" { put("margin", "0") }
        "#history-coverage" {
            width = 100.pct
            marginBottom = 0.px
            padding = Padding(0.75.rem)
        }
        "#history-coverage dl" {
            put("margin", "0")
        }
        "#history-range-custom-panel" {
            alignItems = Align.end
            put("flex", "1 1 100%")
        }
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
        ".${CssClass.History.TradeLog} details summary" {
            put("cursor", "pointer")
            whiteSpaceRaw("nowrap")
        }
        ".${CssClass.History.TradeLog} details dl" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("minmax(8rem, auto) minmax(0, 1fr)")
            gap = 0.375.rem
            minWidthRaw("min(22rem, 72vw)")
            maxWidthRaw("min(36rem, 78vw)")
            marginTop = 0.5.rem
            padding = Padding(0.625.rem)
            background = CssTheme.colorSurface2.value
            solidBorder(CssTheme.colorBorderSubtle)
            borderRadius = CssTheme.radiusSm
            whiteSpaceRaw("normal")
        }
        ".${CssClass.History.TradeLog} details dd" {
            put("margin", "0")
            overflowWrapRaw("anywhere")
        }
        (
            "#${HtmlIds.HISTORY_PORTFOLIO_SECTION}, #${HtmlIds.HISTORY_HOLDINGS_SECTION}, " +
                "#${HtmlIds.HISTORY_ALLOCATION_SECTION}, #${HtmlIds.HISTORY_CASH_FLOW_SECTION}, " +
                "#${HtmlIds.HISTORY_REWARDS_SECTION}, #${HistoryRangeIds.CHECKPOINTS_SECTION}, " +
                "#${HtmlIds.HISTORY_TRADES_SECTION}, " +
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
            ".${CssClass.History.ReportDetails} dl" {
                gridTemplateColumns = GridTemplateColumns("minmax(0, 1fr)")
                gap = 0.125.rem
                put("column-gap", "0.125rem")
            }
            ".${CssClass.History.ReportDetails} dd" {
                textAlign = TextAlign.left
                put("margin-bottom", "0.5rem")
            }
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
