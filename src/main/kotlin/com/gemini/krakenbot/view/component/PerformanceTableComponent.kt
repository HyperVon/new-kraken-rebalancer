package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.Formatter
import kotlinx.html.*

class PerformanceTableComponent {
    fun DIV.render(latest: PortfolioSnapshot) {
        div("glass-panel") {
            h2("glass-panel-title") {
                +"Asset Performance"
            }
            div("table-wrapper") {
                table {
                    thead {
                        tr {
                            th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 0)"; +"Asset" }
                            th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 1)"; +"Price" }
                            th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 2)"; +"Value" }
                            th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 3)"; +"Target %" }
                            th { attributes["class"] = "sortable"; attributes["onclick"] = "sortTable(this, 4)"; +"Current %" }
                            th { attributes["class"] = "sortable asc"; attributes["onclick"] = "sortTable(this, 5)"; +"Dev %" }
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
