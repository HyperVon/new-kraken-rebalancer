package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.DataProps
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ZoomActions
import kotlinx.browser.window
import kotlin.js.json

/** Reusable HTML template String builder functions for Kotlin/JS tests. */
object TestDomBuilders {
    fun chartsDom(): String =
        """
        <canvas id="${HtmlIds.PORTFOLIO_VALUE_CHART}"></canvas>
        <canvas id="${HtmlIds.ASSET_HOLDINGS_CHART}"></canvas>
        <canvas id="${HtmlIds.ALLOCATION_DRIFT_CHART}"></canvas>
        <canvas id="${HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART}"></canvas>
        """.trimIndent()

    fun statsDom(): String =
        """
        <div id="${HtmlIds.STAT_ATH}"></div>
        <div id="${HtmlIds.STAT_TOTAL_TRADES}"></div>
        <div id="${HtmlIds.STAT_TOTAL_VOLUME}"></div>
        <div id="${HtmlIds.STAT_TOTAL_FEES}"></div>
        <div id="${HtmlIds.STAT_AVG_FEE_RATE}"></div>
        <div id="${HtmlIds.STAT_AVG_SLIPPAGE}"></div>
        """.trimIndent()

    fun syncProgressDom(): String =
        """
        <div id="${HtmlIds.SYNC_PROGRESS_BANNER}"></div>
        <div id="${HtmlIds.SYNC_PROGRESS_BAR}"></div>
        <div id="${HtmlIds.SYNC_PROGRESS_TEXT}"></div>
        """.trimIndent()

    fun tradeTableDom(): String =
        """
        <input type="checkbox" id="${HtmlIds.SHOW_DRY_RUN_CHECKBOX}" checked>
        <table><tbody id="${HtmlIds.TRADE_TABLE_BODY}"></tbody></table>
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
        <div class="${CssClass.History.TimeRangeSelector}">
            <button class="${CssClass.History.TimeRangeBtn}" ${HtmlAttrs.DATA_RANGE}="24h">24h</button>
            <button class="${CssClass.History.TimeRangeBtn}" ${HtmlAttrs.DATA_RANGE}="7d">7d</button>
            <button class="${CssClass.History.TimeRangeBtnActive}" ${HtmlAttrs.DATA_RANGE}="30d">30d</button>
        </div>
        <select id="${HtmlIds.HISTORY_VIEWS_SELECT}"></select>
        <button id="${HtmlIds.HISTORY_SAVE_VIEW_BTN}"></button>
        <button id="${HtmlIds.HISTORY_SET_DEFAULT_BTN}"></button>
        <button id="${HtmlIds.HISTORY_DELETE_VIEW_BTN}"></button>
        ${historyDom()}
        """.trimIndent()

    fun settingsDom(): String =
        """
        <span id="${HtmlIds.TOTAL_ALLOCATED_DISPLAY}"></span>
        <button id="${HtmlIds.SAVE_BUTTON}"></button>
        """.trimIndent()

    fun assetEditDom(newSymbol: String = Asset.BTC): String =
        """
        <input type="text" id="${HtmlIds.NEW_SYMBOL_INPUT}" value="$newSymbol">
        <div id="${HtmlIds.ALLOCATIONS_CONTAINER}"></div>
        """.trimIndent()

    fun dataAgeDom(epoch: String = ""): String =
        """
        <span class="${CssClass.DataAge.Value}"></span>
        <span class="${CssClass.DataAge.Time}" ${HtmlAttrs.DATA_EPOCH}="$epoch"></span>
        <span class="${CssClass.StatusCard.Live}"></span>
        """.trimIndent()

    fun sortableTableDom(): String =
        """
        <table>
            <thead>
                <tr>
                    <th class="${CssClass.Table.Sortable}">Col0</th>
                    <th class="${CssClass.Table.Sortable}">Col1</th>
                </tr>
            </thead>
            <tbody>
                <tr class="${CssClass.Table.Hoverable}">
                    <td ${HtmlAttrs.DATA_SORT_VALUE}="10">A</td>
                    <td ${HtmlAttrs.DATA_SORT_VALUE}="20">B</td>
                </tr>
                <tr class="${CssClass.Table.Hoverable}">
                    <td ${HtmlAttrs.DATA_SORT_VALUE}="5">C</td>
                    <td ${HtmlAttrs.DATA_SORT_VALUE}="15">D</td>
                </tr>
            </tbody>
        </table>
        """.trimIndent()

    fun zoomControlsDom(canvasId: String = HtmlIds.PORTFOLIO_VALUE_CHART): String =
        """
        <canvas id="$canvasId"></canvas>
        <button class="${CssClass.History.ZoomBtn}"
          ${HtmlAttrs.DATA_CHART_ID}="$canvasId" ${HtmlAttrs.DATA_ZOOM_ACTION}="${ZoomActions.IN}"></button>
        <button class="${CssClass.History.ZoomBtn}"
          ${HtmlAttrs.DATA_CHART_ID}="$canvasId" ${HtmlAttrs.DATA_ZOOM_ACTION}="${ZoomActions.OUT}"></button>
        <button class="${CssClass.History.ZoomBtn}"
          ${HtmlAttrs.DATA_CHART_ID}="$canvasId" ${HtmlAttrs.DATA_ZOOM_ACTION}="${ZoomActions.RESET}"></button>
        """.trimIndent()

    fun scrubberDom(
        canvasId: String = HtmlIds.PORTFOLIO_VALUE_CHART,
        disabled: Boolean = true,
        value: String = "0",
    ): String {
        val disabledAttr = if (disabled) " disabled" else ""
        return """
        <input class="${CssClass.History.ChartScrubberInput}" type="range" min="0" max="100"
          ${HtmlAttrs.DATA_CHART_ID}="$canvasId" value="$value"$disabledAttr />
        """.trimIndent()
    }

    fun emptyTradeTableDom(): String =
        """
        <table><tbody id="${HtmlIds.TRADE_TABLE_BODY}"></tbody></table>
        """.trimIndent()

    fun emptyTableDom(): String =
        """
        <table><tbody></tbody></table>
        """.trimIndent()

    fun settingsAndSyncDom(): String =
        """
        ${syncProgressDom()}
        <div id="${HtmlIds.ALLOCATIONS_CONTAINER}"></div>
        ${settingsDom()}
        """.trimIndent()

    fun tradeJson(
        timestamp: String = "2023-01-01",
        symbol: String? = Asset.BTC,
        side: String = OrderSide.BUY.name,
        volume: Any? = 0.1,
        usdAmount: Any? = 100.0,
        success: Boolean? = true,
        dryRun: Boolean? = false,
        price: Any? = 50000.0,
        fee: Any? = 2.6,
        slippagePercent: Any? = null,
        expectedPrice: Any? = null,
        source: String? = null,
        errorMessage: String? = null,
    ): dynamic = json(
        DataProps.TIMESTAMP to timestamp,
        DataProps.SYMBOL to symbol,
        DataProps.SIDE to side,
        DataProps.VOLUME to volume,
        DataProps.USD_AMOUNT to usdAmount,
        DataProps.SUCCESS to success,
        DataProps.DRY_RUN to dryRun,
        DataProps.PRICE to price,
        DataProps.FEE to fee,
        DataProps.SLIPPAGE_PERCENT to slippagePercent,
        DataProps.EXPECTED_PRICE to expectedPrice,
        DataProps.SOURCE to source,
        DataProps.ERROR_MESSAGE to errorMessage,
    )

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
        json("label" to label, DataProps.HIDDEN to hidden)
    }
}
