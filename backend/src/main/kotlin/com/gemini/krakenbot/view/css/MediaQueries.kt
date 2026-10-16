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
import kotlinx.css.marginTop
import kotlinx.css.minHeight
import kotlinx.css.minWidth
import kotlinx.css.overflowX
import kotlinx.css.padding
import kotlinx.css.paddingBottom
import kotlinx.css.paddingLeft
import kotlinx.css.paddingRight
import kotlinx.css.paddingTop
import kotlinx.css.pct
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.rowGap
import kotlinx.css.textAlign
import kotlinx.css.width

object MediaQueries {
    fun CssBuilder.applyMediaQueries() {
        "@media (min-width: 640px)" {
            ".${CssClass.History.StatsGrid}" {
                gridTemplateColumns = GridTemplateColumns("repeat(2, 1fr)")
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
            ".${CssClass.History.StatsGrid}" {
                gridTemplateColumns = GridTemplateColumns("repeat(5, 1fr)")
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

        "@media (min-width: 1280px)" {
            CssClass.Hero.Card.querySelector {
                flexDirection = FlexDirection.row
                alignItems = Align.center
            }
            CssClass.Hero.Trend.querySelector {
                put("flex", "1 1 0%")
                put("width", "auto")
            }
        }

        // DASH-2: tablet headers use named grid areas instead of relying on a
        // flex item wrapping at whichever word happens to be next.
        "@media (min-width: 768px) and (max-width: 1023px)" {
            ".${CssClass.Layout.HeaderTitleSection}" {
                display = Display.grid
                gridTemplateColumns = GridTemplateColumns("minmax(0, 1fr) auto")
                rowGap = 0.5.rem
            }
            ".${CssClass.Layout.HeaderWithStream}" {
                put("grid-template-areas", "\"identity status\" \"loop loop\"")
            }
            ".${CssClass.Layout.HeaderWithoutStream}" {
                put("grid-template-areas", "\"identity loop\"")
            }
            ".${CssClass.Layout.HeaderIdentity}" { put("grid-area", "identity") }
            ".${CssClass.Layout.HeaderStatus}" {
                put("grid-area", "status")
                put("justify-self", "end")
            }
            ".${CssClass.Layout.LoopControl}" {
                put("grid-area", "loop")
                put("justify-self", "end")
            }
        }

        "@media (min-width: 1024px)" {
            // DASH-2: wide headers separate page identity from the right-side
            // operational status and control cluster.
            ".${CssClass.Layout.HeaderWithStream} > .${CssClass.Layout.HeaderStatus}" {
                marginLeftRaw("auto")
            }
            ".${CssClass.Layout.HeaderWithoutStream} > .${CssClass.Layout.LoopControl}" {
                marginLeftRaw("auto")
            }
        }

        // Keep the operational header controls compact at laptop widths.
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
        }

        "@media (max-width: 767px)" {
            header {
                flexDirection = FlexDirection.column
                alignItems = Align.center
                rowGap = 0.75.rem
            }
            ".${CssClass.Layout.HeaderTitleSection}" {
                width = 100.pct
                display = Display.grid
                gridTemplateColumns = GridTemplateColumns("minmax(0, 1fr)")
                rowGap = 0.5.rem
            }
            ".${CssClass.Layout.HeaderWithStream}" {
                put("grid-template-areas", "\"identity\" \"status\" \"loop\"")
            }
            ".${CssClass.Layout.HeaderWithoutStream}" {
                put("grid-template-areas", "\"identity\" \"loop\"")
            }
            ".${CssClass.Layout.HeaderIdentity}" {
                put("grid-area", "identity")
                justifyContentRaw("center")
                gap = 0.5.rem
            }
            ".${CssClass.Layout.HeaderTitleSection} h1, .${CssClass.Layout.BrandMark}" {
                fontSize = 1.35.rem
            }
            ".${CssClass.Mode.Plate.value}" {
                padding = Padding(0.25.rem, 0.5.rem)
                fontSize = 0.6.rem
            }
            ".${CssClass.Layout.HeaderStatus}" {
                put("grid-area", "status")
                put("justify-self", "center")
            }
            ".${CssClass.Layout.LoopControl}" {
                put("grid-area", "loop")
                put("justify-self", "center")
            }
            ".${CssClass.Layout.HeaderActions}" {
                display = Display.flex
                width = 100.pct
                justifyContentRaw("center")
                gap = 0.5.rem
                rowGap = 0.5.rem
                flexDirection = FlexDirection.column
                alignItems = Align.center
            }
            ".${CssClass.Layout.HeaderActions} > #${HtmlIds.SAVE_BUTTON}" {
                marginLeftRaw("0")
            }
            ".${CssClass.Layout.GlassPanel}" {
                padding = Padding(1.rem)
            }
            ".hero-card" {
                flexDirection = FlexDirection.column
                gap = 0.75.rem
            }
            ".hero-card > .hero-card-text" {
                flexShrink = 1.0
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
            // SETT-MOBILE-1: the desktop allocation row needs a 30rem track; let the list fit the
            // viewport and stack the controls into two rows so the target and Remove stay reachable.
            // The layout container adds specificity because kotlinx-css emits media rules before
            // unqualified rules, even though this block is registered after the base form styles.
            "${CssClass.Layout.Container.querySelector} .${CssClass.Form.AllocationListContainer}" {
                gridTemplateColumns = GridTemplateColumns("minmax(0, 1fr)")
            }
            "${CssClass.Layout.Container.querySelector} .${CssClass.Form.AllocationEditRow}" {
                gridTemplateColumns = GridTemplateColumns("2rem minmax(0, 1fr) minmax(0, 1fr)")
                gap = 0.5.rem
            }
            ".${CssClass.Form.AllocationControlInputs}" {
                flexWrapRaw("wrap")
            }
            ".${CssClass.Form.AllocationControlInputs} > button" {
                put("flex", "1 1 100%")
            }
            ".${CssClass.Form.AllocationEditSymbol}" {
                put("grid-column", "1 / span 2")
                put("grid-row", "1")
            }
            "${CssClass.Form.AllocationEditRow.querySelector} > label" {
                put("grid-column", "1")
                put("grid-row", "2")
            }
            "${CssClass.Form.AllocationEditInputWrapper.querySelector}:has(input[name=\"scores\"])" {
                put("grid-column", "2")
                put("grid-row", "2")
            }
            "${CssClass.Form.AllocationEditInputWrapper.querySelector}:has(input[name=\"targets\"])" {
                put("grid-column", "3")
                put("grid-row", "2")
            }
            "${CssClass.Form.AllocationEditRow.querySelector} > ${CssClass.Button.DangerGhost.querySelector}" {
                put("grid-column", "3")
                put("grid-row", "1")
                put("justify-self", "end")
            }

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
        }
    }

    private fun quoted(value: String) = QuotedString(value)
}
