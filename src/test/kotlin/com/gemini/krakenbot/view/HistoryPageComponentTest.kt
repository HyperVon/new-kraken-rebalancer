package com.gemini.krakenbot.view

import com.gemini.krakenbot.view.component.HistoryPageComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import kotlinx.html.html
import kotlinx.html.stream.createHTML

@Suppress("unused")
class HistoryPageComponentTest : StringSpec() {
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
            htmlString shouldContain "id=\"cumulative-pl-chart\""
            htmlString shouldContain "id=\"trade-table-body\""
            htmlString shouldContain "history.js"
        }
    }
}
