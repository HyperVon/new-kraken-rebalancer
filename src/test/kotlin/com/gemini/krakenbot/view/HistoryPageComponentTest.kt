package com.gemini.krakenbot.view

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.view.component.HistoryPageComponent
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.html
import kotlinx.html.stream.createHTML

class HistoryPageComponentTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should render HistoryPage HTML structure" {
            val component = HistoryPageComponent(jacksonObjectMapper())
            val settings = TestFixtures.settings(loopDelaySeconds = 60L, minimumOrderSizeUSD = 5.0)
            val htmlString = createHTML().html {
                component.render(settings, csrfToken = "test-csrf-token", paused = true)
            }

            // Raw values keep this boundary test independent from the shared catalog.
            htmlString shouldContain "History - Kraken Rebalancer"
            htmlString shouldContain "id=\"rebalancer-comparison-chart\""
            htmlString shouldContain "id=\"portfolio-value-chart\""
            htmlString shouldContain "id=\"asset-holdings-chart\""
            htmlString shouldContain "id=\"allocation-drift-chart\""
            htmlString shouldContain "id=\"cumulative-net-cash-flow-chart\""
            htmlString shouldContain "id=\"trade-table-body\""
            htmlString shouldContain "id=\"history-views-select\""
            htmlString shouldContain "id=\"history-realtime-root\""
            htmlString shouldContain "id=\"history-save-view-btn\""
            htmlString shouldContain "chartjs-plugin-zoom.min.js"
            htmlString shouldContain "hammer.min.js"
            htmlString shouldContain
                "integrity=\"sha384-vsrfeLOOY6KuIYKDlmVH5UiBmgIdB1oEf7p01YgWHuqmOHfZr374+odEv96n9tNC\""
            htmlString shouldContain
                "integrity=\"sha384-cVMg8E3QFwTvGCDuK+ET4PD341jF3W8nO1auiXfuZNQkzbUUiBGLsIQUE+b1mxws\""
            htmlString shouldContain
                "integrity=\"sha384-Cs3dgUx6+jDxxuqHvVH8Onpyj2LF1gKZurLDlhqzuJmUqVYMJ0THTWpxK5Z086Zm\""
            htmlString shouldContain
                "integrity=\"sha384-dwwI6ICEN/0ZQlS5owhUa/6ZzvwUPmjH45bFVCAcjgjTulbHJvlE+TGU3g1k0N3R\""
            htmlString shouldContain "data-zoom-action=\"in\""
            htmlString shouldContain "history-chart-scrubber-input"
            htmlString shouldContain "id=\"stat-avg-fee-rate\""
            htmlString shouldContain "id=\"stat-avg-slippage\""
            htmlString shouldContain "Price"
            htmlString shouldContain "Fee"
            htmlString shouldContain "Slippage"
            htmlString shouldContain "rebalancer.js"
            htmlString shouldContain "id=\"loop-control\""
            htmlString shouldContain "hx-post=\"/api/resume\""
            htmlString shouldContain "id=\"csrf-token\""
            htmlString shouldContain "https://unpkg.com/htmx.org@2.0.4"
            htmlString shouldContain "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js"
            htmlString shouldContain "hx-ext=\"sse\""
            htmlString shouldContain "sse-connect=\"/api/status/stream\""
            htmlString shouldContain "hx-trigger=\"sse:message\""
            htmlString shouldContain "Rebalancer vs Buy &amp; Hold"
            htmlString shouldContain "comparison-latest-difference"
            htmlString shouldContain "comparison-chart-content"
            htmlString shouldContain "comparison-availability-message"
            htmlString shouldContain "comparison-confidence-badge"
            htmlString shouldContain
                "Based on stored snapshots and recorded trades. Starting quantities are frozen at the first snapshot in the selected range."
            Regex("\\sid=\"rebalancer-comparison-chart\"").findAll(htmlString).count() shouldBe 1
        }

        "should JSON-escape asset colors in window.__ASSET_COLORS__" {
            val component = HistoryPageComponent(jacksonObjectMapper())
            val settings = TestFixtures.settings(loopDelaySeconds = 60L, minimumOrderSizeUSD = 5.0)
            val htmlString = createHTML().html {
                component.render(settings, mapOf("BTC" to "x\"};alert(1);//"))
            }
            htmlString shouldContain "window.__ASSET_COLORS__="
            htmlString shouldContain "\\\"};alert(1);//"
            htmlString shouldNotContain "window.__ASSET_COLORS__={\"BTC\":\"x\"};alert(1);//"
        }
    }
}
