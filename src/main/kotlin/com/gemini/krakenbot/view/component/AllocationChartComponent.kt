package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.Formatter
import kotlinx.html.*

class AllocationChartComponent {
    fun DIV.render(latest: PortfolioSnapshot) {
        div("glass-panel") {
            h2("glass-panel-title") {
                unsafe {
                    +"""<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><path d="M12 2v20"></path><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"></path></svg>"""
                }
                +"Portfolio Allocation (Top Assets)"
            }

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
