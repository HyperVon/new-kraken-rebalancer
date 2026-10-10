package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FontWeight
import kotlinx.css.GridTemplateColumns
import kotlinx.css.Padding
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.gridTemplateColumns
import kotlinx.css.height
import kotlinx.css.marginBottom
import kotlinx.css.marginTop
import kotlinx.css.padding
import kotlinx.css.pct
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.width

object ActualObservationStyles {
    fun CssBuilder.applyActualObservationStyles() {
        ".${CssClass.Actual.Content.value}" {
            display = Display.grid
            gap = 1.rem
        }

        ".${CssClass.Actual.Intro.value}" {
            color = CssTheme.colorTextSecondary
            marginBottom = 0.px
            maxWidthRaw("72rem")
        }

        ".${CssClass.Actual.SummaryGrid.value}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("repeat(2, minmax(0, 1fr))")
            gap = 0.75.rem
            marginTop = 0.75.rem
            marginBottom = 0.75.rem
        }

        ".${CssClass.Actual.SummaryCard.value}" {
            background = CssTheme.colorSurface2.value
            solidBorder(CssTheme.colorSurface2Border)
            borderRadius = CssTheme.radiusMd
            padding = Padding(0.85.rem)
            minWidthRaw("0")
        }

        ".${CssClass.Actual.SummaryLabel.value}" {
            color = CssTheme.colorTextMuted
            fontSize = 0.8.rem
            marginBottom = 0.35.rem
        }

        ".${CssClass.Actual.SummaryValue.value}" {
            color = CssTheme.colorTextPrimary
            fontFamily = CssTheme.fontMono
            fontSize = 1.05.rem
            fontWeight = FontWeight.w600
            overflowWrapRaw("anywhere")
        }

        ".${CssClass.Actual.StateBanner.value}" {
            background = CssTheme.colorBlueGlassBg.value
            solidBorder(CssTheme.colorBlueGlassBorder)
            borderRadius = CssTheme.radiusMd
            color = CssTheme.colorTextSecondary
            marginTop = 0.75.rem
            padding = Padding(0.75.rem)
        }

        ".${CssClass.Actual.Fresh.value}" {
            color = CssTheme.colorSuccess
            fontWeight = FontWeight.w600
        }

        ".${CssClass.Actual.Stale.value}, .${CssClass.Actual.Incomplete.value}" {
            color = CssTheme.colorWarning
            fontWeight = FontWeight.w600
        }

        ".${CssClass.Actual.Muted.value}" {
            color = CssTheme.colorTextMuted
            fontSize = 0.875.rem
            marginTop = 0.65.rem
        }

        ".${CssClass.Actual.Chart.value}" {
            height = 17.rem
            width = 100.pct
            marginTop = 0.5.rem
        }

        ".${CssClass.Actual.ChartSvg.value}" {
            display = Display.block
            height = 100.pct
            width = 100.pct
            overflowRaw("visible")
        }

        ".${CssClass.Actual.GridLine.value}" {
            strokeRaw("rgba(148, 163, 184, 0.24)")
            strokeWidthRaw("1")
        }

        ".${CssClass.Actual.GapMarker.value}" {
            strokeRaw(CssTheme.colorWarning.value)
            strokeDasharrayRaw("4 5")
            strokeWidthRaw("1.5")
        }

        ".${CssClass.Actual.Line.value}" {
            fillRaw("none")
            strokeRaw(CssTheme.colorBlueAccent.value)
            strokeLinecapRaw("round")
            strokeLinejoinRaw("round")
            strokeWidthRaw("3")
        }

        ".${CssClass.Actual.Point.value}" {
            fillRaw(CssTheme.colorBlueAccent.value)
        }

        ".${CssClass.Actual.HoldLine.value}" {
            fillRaw("none")
            strokeRaw(CssTheme.colorGreenAccent.value)
            strokeDasharrayRaw("6 4")
            strokeLinecapRaw("round")
            strokeLinejoinRaw("round")
            strokeWidthRaw("2.5")
        }

        ".${CssClass.Actual.HoldPoint.value}" {
            fillRaw(CssTheme.colorGreenAccent.value)
        }

        ".${CssClass.Actual.ChartLegend.value}" {
            display = Display.flex
            gap = 1.rem
            alignItems = Align.center
            fontSize = 0.85.rem
            marginTop = 0.5.rem
            marginBottom = 0.5.rem
        }

        ".${CssClass.Actual.ChartLegendItem.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.4.rem
            color = CssTheme.colorTextSecondary
        }

        ".${CssClass.Actual.ChartLegendSwatchActual.value}" {
            display = Display.inlineBlock
            width = 12.px
            height = 12.px
            borderRadius = 2.px
            background = CssTheme.colorBlueAccent.value
        }

        ".${CssClass.Actual.ChartLegendSwatchHold.value}" {
            display = Display.inlineBlock
            width = 12.px
            height = 12.px
            borderRadius = 2.px
            background = CssTheme.colorGreenAccent.value
        }

        ".${CssClass.Actual.BenchmarkActions.value}" {
            marginTop = 0.75.rem
        }

        ".${CssClass.Actual.ChartCaption.value}" {
            color = CssTheme.colorTextMuted
            fontSize = 0.8.rem
            marginTop = 0.65.rem
        }

        ".${CssClass.Actual.AssetTable.value}" {
            width = 100.pct
            minWidthRaw("42rem")
        }

        ".${CssClass.Actual.AssetStatus.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.4.rem
        }

        "@media (min-width: 768px)" {
            ".${CssClass.Actual.SummaryGrid.value}" {
                gridTemplateColumns = GridTemplateColumns("repeat(4, minmax(0, 1fr))")
            }
        }

        "@media (max-width: 639px)" {
            ".${CssClass.Actual.SummaryGrid.value}" {
                gridTemplateColumns = GridTemplateColumns("1fr")
            }

            ".${CssClass.Actual.Chart.value}" {
                height = 13.rem
            }
        }
    }
}
