package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*

class AllocationChartComponent {
    fun DIV.render(latest: PortfolioSnapshot) {
        glassPanel(ViewText.PORTFOLIO_ALLOCATION, Icons.DOLLAR_CIRCLE) {
            div("allocation-chart-container") {
                val sorted = latest.assets.values.sortedByDescending { it.valueUSD }
                val topAssets = sorted.take(15)
                val maxVal = if (topAssets.isNotEmpty()) topAssets.first().valueUSD.toDouble() else 1.0

                topAssets.forEach { asset ->
                    val fillPct = if (maxVal > 0) (asset.valueUSD.toDouble() / maxVal * 100).toInt() else 0
                    div("allocation-bar-row") {
                        div("allocation-bar-label") { +asset.symbol }
                        div("allocation-bar-track") {
                            div("allocation-bar-fill") {
                                style = "width: $fillPct%;"
                            }
                        }
                        div("allocation-bar-value") {
                            +"$${Formatter.formatCurrency(asset.valueUSD)} (${Formatter.formatPercent(asset.currentPercent)}%)"
                        }
                    }
                }
            }
        }
    }
}

