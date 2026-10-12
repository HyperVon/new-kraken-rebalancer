package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FlexDirection
import kotlinx.css.FontWeight
import kotlinx.css.GridTemplateColumns
import kotlinx.css.Padding
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.columnGap
import kotlinx.css.display
import kotlinx.css.flexDirection
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

        ".${CssClass.Actual.LatestHero.value}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("minmax(0, 1fr) auto")
            alignItems = Align.end
            gap = 1.rem
            marginTop = 0.75.rem
        }

        ".${CssClass.Actual.MainValue.value}" {
            color = CssTheme.colorTextPrimary
            fontFamily = CssTheme.fontHeading
            fontSize = 2.75.rem
            fontWeight = FontWeight.w700
            lineHeightRaw("1.05")
            letterSpacingRaw("-0.03em")
            fontVariantNumericRaw("tabular-nums")
            overflowWrapRaw("anywhere")
        }

        ".${CssClass.Actual.LatestBadges.value}" {
            display = Display.flex
            flexWrapRaw("wrap")
            justifyContentRaw("flex-end")
            gap = 0.5.rem
        }

        ".${CssClass.Actual.StatusPill.value}" {
            background = CssTheme.colorSurface2.value
            solidBorder(CssTheme.colorSurface2Border)
            borderRadius = CssTheme.radiusPill
            fontSize = 0.75.rem
            padding = Padding(0.35.rem, 0.65.rem)
            whiteSpaceRaw("nowrap")
        }

        ".${CssClass.Actual.LatestDetails.value}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("repeat(3, minmax(0, 1fr))")
            gap = 0.75.rem
            columnGap = 1.25.rem
            marginTop = 1.1.rem
        }

        ".${CssClass.Actual.LatestDetail.value}" {
            minWidthRaw("0")
            overflowWrapRaw("anywhere")
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

        ".${CssClass.Actual.AxisLabel.value}" {
            fillRaw(CssTheme.colorTextMuted.value)
            fontFamily = CssTheme.fontMono
            fontSize = 0.7.rem
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
            minWidthRaw("56rem")
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

            ".${CssClass.Actual.LatestHero.value}" {
                gridTemplateColumns = GridTemplateColumns("1fr")
                alignItems = Align.start
            }

            ".${CssClass.Actual.LatestBadges.value}" {
                justifyContentRaw("flex-start")
            }

            ".${CssClass.Actual.LatestDetails.value}" {
                gridTemplateColumns = GridTemplateColumns("1fr")
            }

            ".${CssClass.Actual.Chart.value}" {
                height = 13.rem
            }

            ".${CssClass.Actual.AssetTable.value}" {
                display = Display.block
                minWidthRaw("0")
            }

            ".${CssClass.Actual.AssetTable.value} thead" {
                put("position", "absolute")
                put("width", "1px")
                put("height", "1px")
                put("padding", "0")
                put("margin", "-1px")
                put("overflow", "hidden")
                put("clip", "rect(0, 0, 0, 0)")
                put("white-space", "nowrap")
                put("border", "0")
            }

            ".${CssClass.Actual.AssetTable.value} tbody" {
                display = Display.grid
                gap = 0.75.rem
            }

            ".${CssClass.Actual.AssetTable.value} tbody tr" {
                display = Display.grid
                gridTemplateColumns = GridTemplateColumns("repeat(2, minmax(0, 1fr))")
                solidBorder(CssTheme.colorSurface2Border)
                borderRadius = CssTheme.radiusMd
                padding = Padding(0.5.rem)
            }

            ".${CssClass.Actual.AssetTable.value} tbody td" {
                display = Display.flex
                flexDirection = FlexDirection.column
                gap = 0.25.rem
                minWidthRaw("0")
                padding = Padding(0.5.rem)
                overflowWrapRaw("anywhere")
            }

            ".${CssClass.Actual.AssetTable.value} tbody td::before" {
                put("content", "attr(data-label)")
                put("color", CssTheme.colorTextMuted.value)
                put("font-size", "0.7rem")
                put("text-transform", "uppercase")
                put("letter-spacing", "0.04em")
            }

            ".${CssClass.Actual.AssetTable.value} tbody td:first-child, " +
                ".${CssClass.Actual.AssetTable.value} tbody td:last-child" {
                    put("grid-column", "1 / -1")
                }

            ".${CssClass.Actual.AssetTable.value} tbody td:first-child" {
                fontWeight = FontWeight.w700
                color = CssTheme.colorTextPrimary
            }
        }
    }
}
