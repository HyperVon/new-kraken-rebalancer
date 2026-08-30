package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.window
import kotlin.js.json

object TestDomBuilders {
    fun chartsDom(): String =
        """
        <div id="comparison-chart-content" class="comparison-chart-area">
            <canvas id="rebalancer-comparison-chart"></canvas>
        </div>
        <div id="comparison-availability-message" class="comparison-unavailable"></div>
        <span id="comparison-confidence-badge" class="comparison-confidence-badge"></span>
        <span id="comparison-latest-difference">${ViewText.EM_DASH}</span>
        <span id="rewards-total">${ViewText.EM_DASH}</span>
        <div id="rewards-chart-content" class="rewards-chart-area">
            <canvas id="rewards-chart"></canvas>
        </div>
        <canvas id="portfolio-value-chart"></canvas>
        <canvas id="asset-holdings-chart"></canvas>
        <canvas id="allocation-drift-chart"></canvas>
        <canvas id="cumulative-net-cash-flow-chart"></canvas>
        """.trimIndent()

    fun statsDom(): String =
        """
        <div id="stat-ath"></div>
        <div id="stat-total-trades"></div>
        <div id="stat-total-volume"></div>
        <div id="stat-total-fees"></div>
        <div id="stat-avg-fee-rate"></div>
        <div id="stat-avg-slippage"></div>
        """.trimIndent()

    fun syncProgressDom(): String =
        """
        <div id="sync-progress-banner"></div>
        <div id="sync-progress-bar"></div>
        <div id="sync-progress-text"></div>
        """.trimIndent()

    fun tradeTableDom(): String =
        """
        <input type="checkbox" id="show-dry-run-checkbox" checked>
        <table><tbody id="trade-table-body"></tbody></table>
        """.trimIndent()

    fun historyDom(): String =
        """
        ${chartsDom()}
        ${tradeTableDom()}
        ${statsDom()}
        ${syncProgressDom()}
        """.trimIndent()

    fun historyViewsDom(): String =
        """
        <div class="time-range-selector">
            <button class="time-range-btn" data-range="24h">24h</button>
            <button class="time-range-btn" data-range="7d">7d</button>
            <button class="time-range-btn active" data-range="30d">30d</button>
        </div>
        <select id="history-views-select"></select>
        <button id="history-save-view-btn"></button>
        <button id="history-set-default-btn"></button>
        <button id="history-delete-view-btn"></button>
        ${historyDom()}
        """.trimIndent()

    fun historyRealtimeDom(): String =
        """
        <div id="history-realtime-root" hx-ext="sse" sse-connect="/api/status/stream">
            ${historyViewsDom()}
        </div>
        """.trimIndent()

    fun settingsDom(): String =
        """
        <span id="mode-plate" class="mode-plate mode-dry-run" title="${ViewText.MODE_DRY_RUN_TITLE}">
          <span class="mode-plate-dot"></span>
          <span id="mode-plate-label">${ViewText.MODE_DRY_RUN}</span>
        </span>
        <input type="checkbox" name="simulation">
        <input type="checkbox" name="dryRun" checked>
        <span id="total-allocated-display"></span>
        <button id="save-button"></button>
        """.trimIndent()

    fun assetEditDom(newSymbol: String = Asset.BTC): String =
        """
        <input type="text" id="new-symbol-input" value="$newSymbol">
        <div id="allocations-container"></div>
        """.trimIndent()

    // StatusCard.Live is "status-badge live", so Badge ("status-badge") queries match production chips.
    fun dataAgeDom(epoch: String = ""): String =
        """
        <span class="data-age-value"></span>
        <span class="data-age-time" data-epoch="$epoch"></span>
        <span class="status-badge live"></span>
        """.trimIndent()

    fun sortableTableDom(): String =
        """
        <table>
            <thead>
                <tr>
                    <th class="sortable">Col0</th>
                    <th class="sortable">Col1</th>
                </tr>
            </thead>
            <tbody>
                <tr class="hoverable">
                    <td data-sort-value="10">A</td>
                    <td data-sort-value="20">B</td>
                </tr>
                <tr class="hoverable">
                    <td data-sort-value="5">C</td>
                    <td data-sort-value="15">D</td>
                </tr>
            </tbody>
        </table>
        """.trimIndent()

    fun zoomControlsDom(canvasId: String = "portfolio-value-chart"): String =
        """
        <canvas id="$canvasId"></canvas>
        <button class="history-zoom-btn"
          data-chart-id="$canvasId" data-zoom-action="in"></button>
        <button class="history-zoom-btn"
          data-chart-id="$canvasId" data-zoom-action="out"></button>
        <button class="history-zoom-btn"
          data-chart-id="$canvasId" data-zoom-action="reset"></button>
        """.trimIndent()

    // Defaults disabled like production until syncChartScrubber sees a zoomed x-window.
    fun scrubberDom(
        canvasId: String = "portfolio-value-chart",
        disabled: Boolean = true,
        value: String = "0",
    ): String {
        val disabledAttr = if (disabled) " disabled" else ""
        return """
        <input class="history-chart-scrubber-input" type="range" min="0" max="100"
          data-chart-id="$canvasId" value="$value"$disabledAttr />
        """.trimIndent()
    }

    fun emptyTradeTableDom(): String =
        """
        <table><tbody id="trade-table-body"></tbody></table>
        """.trimIndent()

    fun emptyTableDom(): String =
        """
        <table><tbody></tbody></table>
        """.trimIndent()

    fun settingsAndSyncDom(): String =
        """
        ${syncProgressDom()}
        <div id="allocations-container"></div>
        ${settingsDom()}
        """.trimIndent()

    // Appends each Chart config to window.chartConfigs for HistoryChartsTest callback assertions.
    fun setupMockChart(isDatasetVisible: (Int) -> Boolean = { true }) {
        var callCount = 0
        window.asDynamic().chartCallCount = 0
        window.asDynamic().chartConfigs = arrayOf<dynamic>()

        val chartConstructor = { _: dynamic, config: dynamic ->
            callCount++
            window.asDynamic().chartCallCount = callCount
            val configs = window.asDynamic().chartConfigs as? Array<dynamic> ?: arrayOf()
            window.asDynamic().chartConfigs = configs + arrayOf(config)

            val mockInstance: dynamic =
                json(
                    "data" to config.data,
                    "destroyCalled" to false,
                    "isDatasetVisible" to { index: Int -> isDatasetVisible(index) },
                    "destroy" to { },
                )
            mockInstance
        }
        window.asDynamic().Chart = chartConstructor
    }

    fun chartConfig(vararg datasets: dynamic): dynamic = json(
        "type" to "line",
        "data" to json("datasets" to datasets),
        "options" to json(),
    )

    fun datasetConfig(label: String, hidden: Boolean? = null): dynamic = if (hidden == null) {
        json("label" to label)
    } else {
        json("label" to label, "hidden" to hidden)
    }
}
