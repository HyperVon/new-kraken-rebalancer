package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.impl.PortfolioCalculations
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.glassPanel
import kotlinx.html.DIV
import kotlinx.html.style
import java.math.BigDecimal
import java.math.RoundingMode

class AllocationChartComponent {
    context(div: DIV)
    fun render(latest: PortfolioSnapshot) {
        div.glassPanel(ViewText.PORTFOLIO_ALLOCATION, Icons.DOLLAR_CIRCLE) {
            div(CssClass.AllocationChart.Container) {
                val sorted = latest.assets.values.sortedByDescending { it.valueUSD }
                val topAssets = sorted.take(15)
                val maxVal =
                    if (topAssets.isNotEmpty()) {
                        topAssets.first().valueUSD
                    } else {
                        BigDecimal.ONE
                    }

                topAssets.forEach { asset ->
                    val fillPct =
                        PortfolioCalculations
                            .calculateCurrentPercent(asset.valueUSD, maxVal)
                            .setScale(0, RoundingMode.HALF_UP)
                            .toInt()
                    div(CssClass.AllocationChart.BarRow) {
                        div(CssClass.AllocationChart.BarLabel) { +asset.symbol.value }
                        div(CssClass.AllocationChart.BarTrack) {
                            div(CssClass.AllocationChart.BarFill) {
                                style = "width: $fillPct%;"
                            }
                        }
                        div(CssClass.AllocationChart.BarValue) {
                            +"$${Formatter.formatCurrency(asset.valueUSD)} (${Formatter.formatPercent(asset.currentPercent)}%)"
                        }
                    }
                }
            }
        }
    }
}
