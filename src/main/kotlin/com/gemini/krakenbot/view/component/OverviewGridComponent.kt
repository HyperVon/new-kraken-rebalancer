package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.Formatter
import kotlinx.html.*
import java.math.BigDecimal
import kotlin.math.abs

class OverviewGridComponent {
    fun DIV.render(latest: PortfolioSnapshot) {
        val totalValue = latest.totalValueUSD
        val usdAsset = latest.assets["USD"]
        val usdValue = usdAsset?.valueUSD ?: BigDecimal.ZERO
        val cryptoValue = totalValue - usdValue

        val assetsList = latest.assets.values.filter { it.symbol != "USD" }
        val cryptoPercent = assetsList.sumOf { it.currentPercent.toDouble() }
        val cryptoTargetPercent = assetsList.sumOf { it.targetPercent.toDouble() }
        val cryptoCount = assetsList.size

        div("overview-grid") {
            div("glass-panel status-card") {
                div("status-card-header") {
                    span("status-card-title") { +"Total Portfolio" }
                    div("status-card-icon") {
                        unsafe {
                            +"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 7 13.5 15.5 8.5 10.5 2 17"></polyline><polyline points="16 7 22 7 22 13"></polyline></svg>"""
                        }
                    }
                }
                div("status-card-value") { +"$${Formatter.formatCurrency(totalValue)}" }
                div("status-card-sub") {
                    val drawdown = latest.drawdownPercent
                    val isDrawdown = drawdown.signum() > 0
                    val colorClass = if (isDrawdown) "text-danger" else ""
                    span(colorClass) {
                        +"Drawdown: ${Formatter.formatPercent(drawdown)}%"
                    }
                }
            }

            div("glass-panel status-card success") {
                div("status-card-header") {
                    span("status-card-title") { +"Cash (USD)" }
                    div("status-card-icon") {
                        unsafe {
                            +"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12V7H5a2 2 0 0 1 2-2h14V4a2 2 0 0 0-2-2H3a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5H7a2 2 0 0 1-2-2h16z"></path></svg>"""
                        }
                    }
                }
                div("status-card-value") { +"$${Formatter.formatCurrency(usdValue)}" }
                div("status-card-sub") {
                    if (usdAsset != null) {
                        val currentPct = usdAsset.currentPercent
                        val targetPct = latest.effectiveUsdTargetPercent
                        val baseTargetPct = usdAsset.targetPercent
                        val dev = usdAsset.deviationPercent
                        val devClass = Formatter.getDeviationClass(dev)
                        val devSign = Formatter.getDeviationSign(dev)

                        span {
                            +"${Formatter.formatPercent(currentPct)}% | Target: ${Formatter.formatPercent(targetPct)}%"
                            if (abs(targetPct.toDouble() - baseTargetPct.toDouble()) > 0.01) {
                                +" (Base: ${Formatter.formatPercent(baseTargetPct)}%)"
                            }
                            +" | "
                            span(devClass) {
                                +"Dev: $devSign${Formatter.formatPercent(dev)}%"
                            }
                        }
                    } else {
                        +"No USD Data"
                    }
                }
            }

            div("glass-panel status-card") {
                div("status-card-header") {
                    span("status-card-title") { +"Crypto Assets" }
                    div("status-card-icon") {
                        unsafe {
                            +"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="8" r="6"></circle><circle cx="18" cy="18" r="4"></circle><path d="M12 18a6 6 0 0 0-6-6"></path></svg>"""
                        }
                    }
                }
                div("status-card-value") { +"$${Formatter.formatCurrency(cryptoValue)}" }
                div("status-card-sub") {
                    span {
                        +"${Formatter.formatPercent(cryptoPercent)}% | Target: ${Formatter.formatPercent(cryptoTargetPercent)}% | $cryptoCount Assets"
                    }
                }
            }
        }
    }
}
