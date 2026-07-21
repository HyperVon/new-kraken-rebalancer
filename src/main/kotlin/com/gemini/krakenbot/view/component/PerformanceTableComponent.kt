package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.Layouts.glassPanel
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.html.*

class PerformanceTableComponent {

    private data class ColumnHeader(
        val label: String,
        val cssClass: String = CssClass.Table.Sortable.value
    )

    private companion object {
        val COLUMNS = listOf(
            ColumnHeader(ViewText.HEADER_ASSET),
            ColumnHeader(ViewText.HEADER_PRICE),
            ColumnHeader(ViewText.HEADER_VALUE),
            ColumnHeader(ViewText.HEADER_TARGET_PCT),
            ColumnHeader(ViewText.HEADER_CURRENT_PCT),
            ColumnHeader(ViewText.HEADER_DEV_PCT, CssClass.Table.SortableAsc.value)
        )
    }

    context(div: DIV)
    fun render(latest: PortfolioSnapshot) {
        div.glassPanel(ViewText.ASSET_PERFORMANCE) {
            div(CssClass.Table.Wrapper.value) {
                table {
                    thead {
                        tr {
                            COLUMNS.forEachIndexed { index, col ->
                                th {
                                    attributes[HtmlAttrs.CLASS] = col.cssClass
                                    attributes[HtmlAttrs.ONCLICK] =
                                        "sortTable(this, $index)"
                                    +col.label
                                }
                            }
                        }
                    }
                    tbody {
                        val cryptoOnly = latest.assets.values
                            .filter { !it.symbol.isUsd }
                            .sortedBy { it.deviationPercent }
                        cryptoOnly.forEach { asset ->
                            val dev = asset.deviationPercent
                            val devClass = Formatter.getDeviationClass(dev)
                            val sign = Formatter.getDeviationSign(dev)

                            tr(CssClass.Table.Hoverable.value) {
                                td(CssClass.Table.SymbolCol.value) { +asset.symbol.value }
                                td(CssClass.Table.MonoCol.value) {
                                    +"$${
                                        Formatter.formatCurrency(
                                            asset.price
                                        )
                                    }"
                                }
                                td(CssClass.Table.MonoCol.value) {
                                    +"$${
                                        Formatter.formatCurrency(
                                            asset.valueUSD
                                        )
                                    }"
                                }
                                td { +"${Formatter.formatPercent(asset.targetPercent)}%" }
                                td { +"${Formatter.formatPercent(asset.currentPercent)}%" }
                                td(devClass) {
                                    attributes[HtmlAttrs.DATA_SORT_VALUE] = asset.deviationPercent.toString()
                                    div(CssClass.Performance.DevContainer.value) {
                                        span {
                                            +"$sign${
                                                Formatter.formatPercent(
                                                    dev
                                                )
                                            }%"
                                        }
                                        span(CssClass.Performance.DevUsdLabel.value) {
                                            val devUSD = asset.deviationUSD
                                            val usdSign =
                                                if (devUSD.signum() >= 0) "+" else ""
                                            +"($usdSign$${
                                                Formatter.formatCurrency(
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
