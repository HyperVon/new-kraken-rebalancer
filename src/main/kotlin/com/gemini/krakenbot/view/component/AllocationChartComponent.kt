package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClasses.ALLOCATION_BAR_FILL
import com.gemini.krakenbot.view.util.CssClasses.ALLOCATION_BAR_LABEL
import com.gemini.krakenbot.view.util.CssClasses.ALLOCATION_BAR_ROW
import com.gemini.krakenbot.view.util.CssClasses.ALLOCATION_BAR_TRACK
import com.gemini.krakenbot.view.util.CssClasses.ALLOCATION_BAR_VALUE
import com.gemini.krakenbot.view.util.CssClasses.ALLOCATION_CHART_CONTAINER
import com.gemini.krakenbot.view.util.Formatter.formatCurrency
import com.gemini.krakenbot.view.util.Formatter.formatPercent
import com.gemini.krakenbot.view.util.Icons.DOLLAR_CIRCLE
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText.PORTFOLIO_ALLOCATION
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.style

class AllocationChartComponent {
    fun DIV.render(latest: PortfolioSnapshot) {
        glassPanel(PORTFOLIO_ALLOCATION, DOLLAR_CIRCLE) {
            div(ALLOCATION_CHART_CONTAINER) {
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
                    div(ALLOCATION_BAR_ROW) {
                        div(ALLOCATION_BAR_LABEL) { +asset.symbol }
                        div(ALLOCATION_BAR_TRACK) {
                            div(ALLOCATION_BAR_FILL) {
                                style = "width: $fillPct%;"
                            }
                        }
                        div(ALLOCATION_BAR_VALUE) {
                            +"$${formatCurrency(asset.valueUSD)} (${
                                formatPercent(
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

