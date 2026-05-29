package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClasses
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.util.KrakenSymbols
import kotlinx.html.*

class PerformanceTableComponent {

    private data class ColumnHeader(val label: String, val cssClass: String = CssClasses.SORTABLE)

    private companion object {
        val COLUMNS = listOf(
            ColumnHeader(ViewText.HEADER_ASSET),
            ColumnHeader(ViewText.HEADER_PRICE),
            ColumnHeader(ViewText.HEADER_VALUE),
            ColumnHeader(ViewText.HEADER_TARGET_PCT),
            ColumnHeader(ViewText.HEADER_CURRENT_PCT),
            ColumnHeader(ViewText.HEADER_DEV_PCT, CssClasses.SORTABLE_ASC)
        )
    }

    fun DIV.render(latest: PortfolioSnapshot) {
        glassPanel(ViewText.ASSET_PERFORMANCE) {
            div(CssClasses.TABLE_WRAPPER) {
                table {
                    thead {
                        tr {
                            COLUMNS.forEachIndexed { index, col ->
                                th {
                                    attributes[HtmlAttrs.CLASS] = col.cssClass
                                    attributes[HtmlAttrs.ONCLICK] = "sortTable(this, $index)"
                                    +col.label
                                }
                            }
                        }
                    }
                    tbody {
                        val cryptoOnly = latest.assets.values
                            .filter { it.symbol != KrakenSymbols.USD }
                            .sortedBy { it.deviationPercent }
                        cryptoOnly.forEach { asset ->
                            val dev = asset.deviationPercent
                            val devClass = Formatter.getDeviationClass(dev)
                            val sign = Formatter.getDeviationSign(dev)

                            tr(CssClasses.HOVERABLE) {
                                td(CssClasses.SYMBOL_COL) { +asset.symbol }
                                td(CssClasses.MONO_COL) { +"$${Formatter.formatCurrency(asset.price)}" }
                                td(CssClasses.MONO_COL) { +"$${Formatter.formatCurrency(asset.valueUSD)}" }
                                td { +"${Formatter.formatPercent(asset.targetPercent)}%" }
                                td { +"${Formatter.formatPercent(asset.currentPercent)}%" }
                                td(devClass) {
                                    div(CssClasses.PERFORMANCE_DEV_CONTAINER) {
                                        span { +"$sign${Formatter.formatPercent(dev)}%" }
                                        span(CssClasses.PERFORMANCE_DEV_USD_LABEL) {
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
