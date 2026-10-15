package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.CssBuilder
import kotlinx.css.Display
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
        ".${CssClass.History.ToolbarRow}" {
            display = Display.flex
            alignItems = Align.center
            flexWrapRaw("wrap")
            gap = 0.75.rem
            marginBottom = 1.rem
        }
        ".${CssClass.History.TimeRangeSelector}" {
            display = Display.flex
            gap = 0.375.rem
            flexWrapRaw("wrap")
        }
        ".${CssClass.History.TimeRangeBtn}" {
            padding = Padding(0.375.rem, 1.rem)
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
        }
        ".${CssClass.History.ChartHeader}" {
            display = Display.flex
            alignItems = Align.center
            marginBottom = 0.5.rem
        }
        ".${CssClass.History.ChartHeaderTitle}, .${CssClass.History.TitleNoMargin}" {
            marginBottom = 0.px
        }
        ".${CssClass.History.ChartContainer}" {
            height = 20.rem
            position = Position.relative
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
            color = CssTheme.colorTextMuted
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
        ".${CssClass.History.FilterInput}" {
            padding = Padding(0.375.rem, 0.625.rem)
            fontSize = 0.8125.rem
            width = 12.rem
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorSurface2.value
            solidBorder(CssTheme.colorSurface2Border)
            borderRadius = CssTheme.radiusMd
        }
        ".${CssClass.History.PaginationBtn}" {
            padding = Padding(0.25.rem, 0.75.rem)
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            borderRadius = CssTheme.radiusSm
        }
        ".${CssClass.History.SyncBanner}" {
            marginBottom = 1.rem
            padding = Padding(1.rem)
        }
        ".${CssClass.History.SyncHeader}, .${CssClass.History.SyncTitle}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
        }
        ".${CssClass.History.SyncText}" {
            fontSize = 0.8125.rem
            color = CssTheme.colorTextSecondary
        }
    }
}
