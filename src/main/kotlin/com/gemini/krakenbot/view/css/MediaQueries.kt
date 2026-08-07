package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.CssBuilder
import kotlinx.css.FlexDirection
import kotlinx.css.GridTemplateColumns
import kotlinx.css.Padding
import kotlinx.css.alignItems
import kotlinx.css.flexDirection
import kotlinx.css.fontSize
import kotlinx.css.gap
import kotlinx.css.gridTemplateColumns
import kotlinx.css.header
import kotlinx.css.marginBottom
import kotlinx.css.padding
import kotlinx.css.paddingBottom
import kotlinx.css.pct
import kotlinx.css.px
import kotlinx.css.rem

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
                flexDirection = FlexDirection.row
                justifyContentRaw("space-between")
                alignItems = Align.center
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

        // CI-11-UI6: tighten History 9-col trade log at laptop widths (~1280).
        "@media (max-width: 1280px)" {
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
    }
}
