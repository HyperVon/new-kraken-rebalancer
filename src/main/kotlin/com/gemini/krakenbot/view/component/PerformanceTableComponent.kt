package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.util.KrakenSymbols
import com.gemini.krakenbot.view.util.CssClasses.HOVERABLE
import com.gemini.krakenbot.view.util.CssClasses.MONO_COL
import com.gemini.krakenbot.view.util.CssClasses.PERFORMANCE_DEV_CONTAINER
import com.gemini.krakenbot.view.util.CssClasses.PERFORMANCE_DEV_USD_LABEL
import com.gemini.krakenbot.view.util.CssClasses.SORTABLE
import com.gemini.krakenbot.view.util.CssClasses.SORTABLE_ASC
import com.gemini.krakenbot.view.util.CssClasses.SYMBOL_COL
import com.gemini.krakenbot.view.util.CssClasses.TABLE_WRAPPER
import com.gemini.krakenbot.view.util.Formatter.formatCurrency
import com.gemini.krakenbot.view.util.Formatter.formatPercent
import com.gemini.krakenbot.view.util.Formatter.getDeviationClass
import com.gemini.krakenbot.view.util.Formatter.getDeviationSign
import com.gemini.krakenbot.view.util.HtmlAttrs.CLASS
import com.gemini.krakenbot.view.util.HtmlAttrs.ONCLICK
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText.ASSET_PERFORMANCE
import com.gemini.krakenbot.view.util.ViewText.HEADER_ASSET
import com.gemini.krakenbot.view.util.ViewText.HEADER_CURRENT_PCT
import com.gemini.krakenbot.view.util.ViewText.HEADER_DEV_PCT
import com.gemini.krakenbot.view.util.ViewText.HEADER_PRICE
import com.gemini.krakenbot.view.util.ViewText.HEADER_TARGET_PCT
import com.gemini.krakenbot.view.util.ViewText.HEADER_VALUE
import kotlinx.html.*

class PerformanceTableComponent {

    private data class ColumnHeader(
        val label: String,
        val cssClass: String = SORTABLE
    )

    private companion object {
        val COLUMNS = listOf(
            ColumnHeader(HEADER_ASSET),
            ColumnHeader(HEADER_PRICE),
            ColumnHeader(HEADER_VALUE),
            ColumnHeader(HEADER_TARGET_PCT),
            ColumnHeader(HEADER_CURRENT_PCT),
            ColumnHeader(HEADER_DEV_PCT, SORTABLE_ASC)
        )
    }

    fun DIV.render(latest: PortfolioSnapshot) {
        glassPanel(ASSET_PERFORMANCE) {
            div(TABLE_WRAPPER) {
                table {
                    thead {
                        tr {
                            COLUMNS.forEachIndexed { index, col ->
                                th {
                                    attributes[CLASS] = col.cssClass
                                    attributes[ONCLICK] =
                                        "sortTable(this, $index)"
                                    +col.label
                                }
                            }
                        }
                    }
                    tbody {
                        val cryptoOnly = latest.assets.values
                            .filter { it.symbol.value != KrakenSymbols.USD }
                            .sortedBy { it.deviationPercent }
                        cryptoOnly.forEach { asset ->
                            val dev = asset.deviationPercent
                            val devClass = getDeviationClass(dev)
                            val sign = getDeviationSign(dev)
 
                            tr(HOVERABLE) {
                                td(SYMBOL_COL) { +asset.symbol.value }
                                td(MONO_COL) {
                                    +"$${
                                        formatCurrency(
                                            asset.price
                                        )
                                    }"
                                }
                                td(MONO_COL) {
                                    +"$${
                                        formatCurrency(
                                            asset.valueUSD
                                        )
                                    }"
                                }
                                td { +"${formatPercent(asset.targetPercent)}%" }
                                td { +"${formatPercent(asset.currentPercent)}%" }
                                td(devClass) {
                                    div(PERFORMANCE_DEV_CONTAINER) {
                                        span {
                                            +"$sign${
                                                formatPercent(
                                                    dev
                                                )
                                            }%"
                                        }
                                        span(PERFORMANCE_DEV_USD_LABEL) {
                                            val devUSD = asset.deviationUSD
                                            val usdSign =
                                                if (devUSD.signum() >= 0) "+" else ""
                                            +"($usdSign$${
                                                formatCurrency(
                                                    devUSD
                                                )
                                            })"
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
