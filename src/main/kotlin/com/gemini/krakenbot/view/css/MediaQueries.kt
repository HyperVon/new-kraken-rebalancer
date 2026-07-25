package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.*

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
                put("flex-wrap", "nowrap")
            }
        }

        "@media (min-width: 768px)" {
            ".${CssClass.Layout.Container}" {
                padding = Padding(1.5.rem, 1.5.rem, 4.rem, 1.5.rem)
            }
            header {
                flexDirection = FlexDirection.row
                put("justify-content", "space-between")
                alignItems = Align.center
                paddingBottom = 1.5.rem
                marginBottom = 1.75.rem
            }
            "${CssClass.StatusCard.Default.querySelector}" {
                gap = 0.375.rem
                padding = Padding(0.75.rem, 0.875.rem)
            }
            ".${CssClass.Layout.OverviewGrid}" {
                gridTemplateColumns = GridTemplateColumns("repeat(3, 1fr)")
                gap = 0.75.rem
                alignItems = Align.start
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
            ".${CssClass.Form.SafetyToggles}" {
                flexDirection = FlexDirection.row
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
    }
}
