package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.domain.QualityAllocation
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.Icons
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.glassPanel
import com.gemini.krakenbot.view.util.solidColorForSymbol
import com.gemini.krakenbot.view.util.symbolColorMap
import com.gemini.krakenbot.view.util.withQuery
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.style
import java.math.BigDecimal
import java.math.RoundingMode

class AllocationChartComponent {
    context(div: DIV)
    fun render(
        latest: PortfolioSnapshot,
        allocations: List<Allocation> = emptyList(),
        qualityScores: Map<String, BigDecimal> = emptyMap(),
    ) {
        val colorMap = allocations.symbolColorMap()
        div.glassPanel(ViewText.PORTFOLIO_ALLOCATION, Icons.DOLLAR_CIRCLE) {
            renderQualityProfile(latest, qualityScores)
            div(CssClass.AllocationChart.Container) {
                val sorted = latest.assets.values.sortedByDescending { it.valueUSD }
                val topAssets = sorted.take(15)
                val maxVal =
                    if (topAssets.isNotEmpty()) {
                        topAssets.first().valueUSD
                    } else {
                        BigDecimal.ONE
                    }

                topAssets.forEachIndexed { index, asset ->
                    val fillPct =
                        if (maxVal.signum() > 0) {
                            asset.valueUSD
                                .multiply(BigDecimal(100))
                                .divide(maxVal, 0, RoundingMode.HALF_UP)
                                .toInt()
                        } else {
                            0
                        }
                    val barColor = colorMap[asset.symbol.value.uppercase()]
                        ?: solidColorForSymbol(asset.symbol.value, index)
                    div(CssClass.AllocationChart.BarRow) {
                        div(CssClass.AllocationChart.BarLabel) {
                            a(href = Routes.HISTORY.withQuery("asset", asset.symbol.value)) {
                                +asset.symbol.value
                            }
                        }
                        div(CssClass.AllocationChart.BarTrack) {
                            div(CssClass.AllocationChart.BarFill) {
                                style = "width: $fillPct%; background-color: $barColor;"
                            }
                        }
                        div(CssClass.AllocationChart.BarValue) {
                            +"$${Formatter.formatCurrency(
                                asset.valueUSD,
                            )} (${Formatter.formatPercent(asset.currentPercent)}%)"
                        }
                    }
                }
            }
        }
    }

    /**
     * Quality/structure reading for the current book. Renders nothing when no scores are
     * configured, so an unscored deployment sees exactly the panel it saw before.
     */
    private fun FlowContent.renderQualityProfile(latest: PortfolioSnapshot, qualityScores: Map<String, BigDecimal>) {
        if (qualityScores.isEmpty()) return
        val values = latest.assets.mapValues { it.value.valueUSD }
        val profile = QualityAllocation.profile(values, qualityScores) ?: return
        div(CssClass.Form.AllocationStatRow) {
            qualityMetric(
                profile.weightedScore.toPlainString(),
                ViewText.ALLOCATION_QUALITY_SCORE,
                ViewText.ALLOCATION_SCORED_ONLY,
            )
            qualityMetric(
                "${profile.maxWeightPercent.toPlainString()}%",
                ViewText.ALLOCATION_MAX_WEIGHT,
                ViewText.ALLOCATION_WHOLE_BOOK,
            )
            qualityMetric(
                profile.effectiveAssetCount.toPlainString(),
                ViewText.ALLOCATION_EFFECTIVE_BETS,
                ViewText.ALLOCATION_WHOLE_BOOK,
            )
        }
    }

    private fun FlowContent.qualityMetric(value: String, label: String, note: String) {
        div(CssClass.Form.AllocationStat) {
            div(CssClass.Form.AllocationStatValue) { +value }
            div(CssClass.Form.AllocationStatLabel) { +label }
            div(CssClass.Form.AllocationStatNote) { +note }
        }
    }
}
