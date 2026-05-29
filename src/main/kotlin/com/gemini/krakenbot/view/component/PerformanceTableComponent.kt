package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*

class PerformanceTableComponent {
    fun DIV.render(latest: PortfolioSnapshot) {
        glassPanel(ViewText.ASSET_PERFORMANCE) {
            div("table-wrapper") {
                table {
                    thead {
                        tr {
                            th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 0)"; +ViewText.HEADER_ASSET }
                            th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 1)"; +ViewText.HEADER_PRICE }
                            th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 2)"; +ViewText.HEADER_VALUE }
                            th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 3)"; +ViewText.HEADER_TARGET_PCT }
                            th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 4)"; +ViewText.HEADER_CURRENT_PCT }
                            th { attributes["class"] = "sortable asc"; attributes["onclick"] = "sortTable(this, 5)"; +ViewText.HEADER_DEV_PCT }
                        }
                    }
                    tbody {
                        val cryptoOnly = latest.assets.values.filter { it.symbol != "USD" }.sortedBy { it.deviationPercent }
                        cryptoOnly.forEach { asset ->
                            val dev = asset.deviationPercent
                            val devClass = Formatter.getDeviationClass(dev)
                            val sign = Formatter.getDeviationSign(dev)

                            tr("hoverable") {
                                td("symbol-col") { +asset.symbol }
                                td("mono-col") { +"$${Formatter.formatCurrency(asset.price)}" }
                                td("mono-col") { +"$${Formatter.formatCurrency(asset.valueUSD)}" }
                                td { +"${Formatter.formatPercent(asset.targetPercent)}%" }
                                td { +"${Formatter.formatPercent(asset.currentPercent)}%" }
                                td(devClass) {
                                    div {
                                        style = "display: flex; flex-direction: column; line-height: 1.1;"
                                        span { +"$sign${Formatter.formatPercent(dev)}%" }
                                        span {
                                            style = "font-size: 0.675rem; opacity: 0.7; font-family: monospace;"
                                            val devUSD = asset.deviationUSD
                                            val usdSign = if (devUSD.signum() >= 0) "+" else ""
                                            +"($usdSign$${Formatter.formatCurrency(devUSD)})"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

