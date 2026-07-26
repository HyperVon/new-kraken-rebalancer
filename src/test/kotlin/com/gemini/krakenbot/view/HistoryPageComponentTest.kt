package com.gemini.krakenbot.view

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.util.CdnUrls
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
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
            val settings = Settings(
                loopDelaySeconds = 60L,
                deviationTriggerPercent = 2.0,
                dryRun = true,
            )
            val htmlString = createHTML().html {
                component.render(settings)
            }

            // Shared IDs/classes are the contract between server-rendered markup and Kotlin/JS;
            // using :common constants here makes either side's drift fail this test.
            htmlString shouldContain "History - Kraken Rebalancer"
            htmlString shouldContain "id=\"${HtmlIds.PORTFOLIO_VALUE_CHART}\""
            htmlString shouldContain "id=\"${HtmlIds.ASSET_HOLDINGS_CHART}\""
            htmlString shouldContain "id=\"${HtmlIds.ALLOCATION_DRIFT_CHART}\""
            htmlString shouldContain "id=\"${HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART}\""
            htmlString shouldContain "id=\"${HtmlIds.TRADE_TABLE_BODY}\""
            htmlString shouldContain "id=\"${HtmlIds.HISTORY_VIEWS_SELECT}\""
            htmlString shouldContain "id=\"${HtmlIds.HISTORY_SAVE_VIEW_BTN}\""
            htmlString shouldContain CdnUrls.CHART_JS_ZOOM.substringAfterLast('/')
            htmlString shouldContain CdnUrls.HAMMER_JS.substringAfterLast('/')
            htmlString shouldContain "data-zoom-action=\"in\""
            htmlString shouldContain CssClass.History.ChartScrubberInput.value
            htmlString shouldContain "aria-label=\"Pan zoomed chart"
            htmlString shouldContain "id=\"${HtmlIds.STAT_AVG_FEE_RATE}\""
            htmlString shouldContain "id=\"${HtmlIds.STAT_AVG_SLIPPAGE}\""
            htmlString shouldContain ViewText.HEADER_PRICE
            htmlString shouldContain ViewText.HEADER_FEE
            htmlString shouldContain ViewText.HEADER_SLIPPAGE
            htmlString shouldContain "rebalancer.js"
        }
    }
}
