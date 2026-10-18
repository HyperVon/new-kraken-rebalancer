package com.gemini.krakenbot.view.component

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.DataSort
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlKeys
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div
import com.gemini.krakenbot.view.util.glassPanel
import com.gemini.krakenbot.view.util.span
import com.gemini.krakenbot.view.util.td
import com.gemini.krakenbot.view.util.th
import com.gemini.krakenbot.view.util.tr
import com.gemini.krakenbot.view.util.withQuery
import kotlinx.html.DIV
import kotlinx.html.a
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.thead
import java.math.BigDecimal

class PerformanceTableComponent {

    private data class ColumnHeader(
        val label: String,
        val cssClass: CssClass = CssClass.Table.Sortable,
        val sortState: String = DataSort.NONE,
    )

    private companion object {
        val COLUMNS = listOf(
            ColumnHeader(ViewText.HEADER_ASSET),
            ColumnHeader(ViewText.HEADER_PRICE),
            ColumnHeader(ViewText.HEADER_VALUE),
            ColumnHeader(ViewText.HEADER_TARGET_PCT),
            ColumnHeader(ViewText.HEADER_CURRENT_PCT),
            ColumnHeader(ViewText.HEADER_DEV_PCT, CssClass.Table.SortableAsc, DataSort.ASCENDING),
            ColumnHeader(ViewText.HEADER_QUANTITY),
            ColumnHeader(ViewText.ALLOCATION_SCORE_FIELD),
        )
    }

    context(div: DIV)
    fun render(latest: PortfolioSnapshot, qualityScores: Map<String, BigDecimal> = emptyMap()) {
        div.glassPanel(ViewText.ASSET_PERFORMANCE) {
            div(CssClass.Performance.DevLegend) {
                span(CssClass.Performance.DevLegendItem + CssClass.Performance.DevLegendOver) {
                    +ViewText.LEGEND_OVER_TARGET
                }
                span(CssClass.Performance.DevLegendItem + CssClass.Performance.DevLegendUnder) {
                    +ViewText.LEGEND_UNDER_TARGET
                }
            }
            div(CssClass.Table.Wrapper) {
                table {
                    thead {
                        tr {
                            COLUMNS.forEachIndexed { index, col ->
                                th(col.cssClass) {
                                    attributes[HtmlAttrs.ONCLICK] =
                                        "sortTable(this, $index)"
                                    attributes[HtmlAttrs.DATA_SORT] = col.sortState
                                    attributes[HtmlAttrs.TAB_INDEX] = "0"
                                    attributes[HtmlAttrs.ONKEYDOWN] =
                                        "if(event.key === '${HtmlKeys.ENTER}' || " +
                                        "event.key === '${HtmlKeys.SPACE}') { " +
                                        "event.preventDefault(); sortTable(this, $index); }"
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

                            tr(CssClass.Table.Hoverable) {
                                td(CssClass.Table.SymbolCol) {
                                    a(href = Routes.HISTORY.withQuery("asset", asset.symbol.value)) {
                                        +asset.symbol.value
                                    }
                                }
                                td(CssClass.Table.MonoCol) {
                                    attributes[HtmlAttrs.DATA_SORT_VALUE] = asset.price.toPlainString()
                                    +Formatter.formatPriceOrDash(asset.price)
                                }
                                td(CssClass.Table.MonoCol) {
                                    attributes[HtmlAttrs.DATA_SORT_VALUE] = asset.valueUSD.toPlainString()
                                    +Formatter.formatSignedUsd(asset.valueUSD)
                                }
                                td { +"${Formatter.formatPercent(asset.targetPercent)}%" }
                                td { +"${Formatter.formatPercent(asset.currentPercent)}%" }
                                td(devClass) {
                                    attributes[HtmlAttrs.DATA_SORT_VALUE] = asset.deviationPercent.toString()
                                    div(CssClass.Performance.DevContainer) {
                                        span {
                                            +"$sign${
                                                Formatter.formatPercent(
                                                    dev,
                                                )
                                            }%"
                                        }
                                        span(CssClass.Performance.DevUsdLabel) {
                                            val devUSD = asset.deviationUSD
                                            val usdSign = if (devUSD.signum() > 0) "+" else ""
                                            +"($usdSign${Formatter.formatSignedUsd(devUSD)})"
                                        }
                                    }
                                }
                                td(CssClass.Table.MonoCol) {
                                    attributes[HtmlAttrs.DATA_SORT_VALUE] = asset.balance.toPlainString()
                                    +Formatter.formatQuantity(asset.balance)
                                }
                                td(CssClass.Table.MonoCol) {
                                    val score = qualityScores[asset.symbol.value]
                                    if (score != null) {
                                        attributes[HtmlAttrs.DATA_SORT_VALUE] = score.toPlainString()
                                    }
                                    +(score?.let(Formatter::formatPercent) ?: ViewText.EM_DASH)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
