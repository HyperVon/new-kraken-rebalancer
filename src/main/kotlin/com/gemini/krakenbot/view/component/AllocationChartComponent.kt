package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClasses
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.style

class AllocationChartComponent {
    context(div: DIV)
    fun render(latest: PortfolioSnapshot) {
        div.glassPanel(ViewText.PORTFOLIO_ALLOCATION, Icons.DOLLAR_CIRCLE) {
            div(CssClasses.ALLOCATION_CHART_CONTAINER) {
                val sorted =
                    latest.assets.values.sortedByDescending { it.valueUSD }
                val topAssets = sorted.take(15)
                val maxVal =
                    if (topAssets.isNotEmpty()) {
                        topAssets.first().valueUSD.toDouble()
                    } else 1.0

                topAssets.forEach { asset ->
                    val fillPct =
                        if (maxVal > 0) {
                            (asset.valueUSD.toDouble() / maxVal * 100).toInt()
                        } else 0
                    div(CssClasses.ALLOCATION_BAR_ROW) {
                        div(CssClasses.ALLOCATION_BAR_LABEL) { +asset.symbol.value }
                        div(CssClasses.ALLOCATION_BAR_TRACK) {
                            div(CssClasses.ALLOCATION_BAR_FILL) {
                                style = "width: $fillPct%;"
                            }
                        }
                        div(CssClasses.ALLOCATION_BAR_VALUE) {
                            +"$${Formatter.formatCurrency(asset.valueUSD)} (${
                                Formatter.formatPercent(
                                    asset.currentPercent
                                )
                            }%)"
                        }
                    }
                }
            }
        }
    }
}

