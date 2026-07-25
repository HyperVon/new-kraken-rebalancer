package com.gemini.krakenbot.view

import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import kotlinx.html.html
import kotlinx.html.stream.createHTML

@Suppress("unused")
class HistoryPageComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should render HistoryPage HTML structure" {
            val component = HistoryPageComponent()
            val htmlString = createHTML().html {
                component.render()
            }

            htmlString shouldContain "History - Kraken Rebalancer"
            htmlString shouldContain "id=\"portfolio-value-chart\""
            htmlString shouldContain "id=\"asset-holdings-chart\""
            htmlString shouldContain "id=\"allocation-drift-chart\""
            htmlString shouldContain "id=\"cumulative-net-cash-flow-chart\""
            htmlString shouldContain "id=\"trade-table-body\""
            htmlString shouldContain "id=\"history-views-select\""
            htmlString shouldContain "id=\"history-save-view-btn\""
            htmlString shouldContain "chartjs-plugin-zoom"
            htmlString shouldContain "hammer.min.js"
            htmlString shouldContain "data-zoom-action=\"in\""
            htmlString shouldContain "history-chart-scrubber-input"
            htmlString shouldContain "aria-label=\"Pan zoomed chart"
            htmlString shouldContain "id=\"stat-avg-fee-rate\""
            htmlString shouldContain "id=\"stat-avg-slippage\""
            htmlString shouldContain ViewText.HEADER_PRICE
            htmlString shouldContain ViewText.HEADER_FEE
            htmlString shouldContain ViewText.HEADER_SLIPPAGE
            htmlString shouldContain "rebalancer.js"
        }
    }
}
