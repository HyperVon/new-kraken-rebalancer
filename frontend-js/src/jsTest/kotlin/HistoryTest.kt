package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.HtmlIds
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.*
import kotlin.js.json
import kotlin.test.assertEquals

private const val USD = "USD"
private const val DIV = "div"

@Suppress("unused")
class HistoryTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "format$USD renders currency amounts" {
        formatUSD(1234.56) shouldBe "$1,234.56"
        formatUSD(0.0) shouldBe "$0.00"
        formatUSD(-12.3456) shouldBe "$-12.35"
    }

        "formatPair handles valid and missing symbols" {
        val trade1: dynamic = js("({ symbol: '${Asset.BTC}' })")
        formatPair(trade1) shouldBe "${Asset.BTC}/$USD"

        val trade2: dynamic = js("({ symbol: null })")
        formatPair(trade2) shouldBe ""

        val trade3: dynamic = js("({})")
        formatPair(trade3) shouldBe ""

        formatPair(null) shouldBe ""
    }

        "getUniqueSymbols filters and sorts symbols" {
        val snapshots = arrayOf(
            js("({ assets: { BTC: {}, ETH: {}, $USD: {} } })"),
            js("({ assets: { BTC: {}, SOL: {}, $USD: {} } })"),
            js("({ assets: null })")
        )

        val symbolsExcludeUsd = getUniqueSymbols(snapshots, excludeUsd = true)
        symbolsExcludeUsd shouldBe listOf(Asset.BTC, Asset.ETH, Asset.SOL)

        val symbolsIncludeUsd = getUniqueSymbols(snapshots, excludeUsd = false)
        symbolsIncludeUsd shouldBe listOf(Asset.BTC, Asset.ETH, Asset.SOL, Asset.USD)
    }

        "mapSnapshotsToPoints retains timestamps and values" {
        val snapshots = arrayOf(
            js("({ timestamp: '2023-01-01', value: 100.0 })"),
            js("({ timestamp: '2023-01-02', value: 110.0 })")
        )

        val points = mapSnapshotsToPoints(snapshots) { it.value.toString().toDouble() }
        points.size shouldBe 2
        val p0 = points[0]
        val p0x = p0.x
        assertEquals("2023-01-01", p0x.toString())
    }

        "calculateCumulativePL filters and orders completed trades" {
        val trades = arrayOf(
            TestDomBuilders.tradeJson(
                timestamp = "2023-01-01T10:00:00Z",
                side = OrderSide.BUY.name,
                usdAmount = 100.0
            ),
            TestDomBuilders.tradeJson(
                timestamp = "2023-01-01T08:00:00Z",
                side = OrderSide.SELL.name,
                usdAmount = 50.0
            ),
            TestDomBuilders.tradeJson(
                timestamp = "2023-01-01T09:00:00Z",
                success = false,
                side = OrderSide.BUY.name,
                usdAmount = 200.0
            ),
            TestDomBuilders.tradeJson(
                timestamp = "2023-01-01T11:00:00Z",
                dryRun = true,
                side = OrderSide.BUY.name,
                usdAmount = 300.0
            ),
            TestDomBuilders.tradeJson(
                timestamp = "2023-01-01T12:00:00Z",
                side = OrderSide.SELL.name,
                usdAmount = 80.0
            )
        )

        val result = calculateCumulativePL(trades)

        result.size shouldBe 3
        val r0 = result[0]
        val r0y = r0.y
        assertEquals(50.0, r0y.toString().toDouble())
    }

        "renderTradeTable filters dry runs and displays empty states" {
        val container = document.createElement(DIV)
        container.innerHTML = TestDomBuilders.tradeTableDom()
        document.body!!.appendChild(container)

        try {
            val trades = arrayOf(
                TestDomBuilders.tradeJson(
                    timestamp = "2023-01-01",
                    symbol = Asset.BTC,
                    side = OrderSide.BUY.name,
                    volume = 0.1,
                    usdAmount = 2000.0,
                    success = true,
                    dryRun = false
                ),
                TestDomBuilders.tradeJson(
                    timestamp = "2023-01-02",
                    symbol = Asset.ETH,
                    side = OrderSide.SELL.name,
                    volume = 1.0,
                    usdAmount = 1800.0,
                    success = true,
                    dryRun = true
                ),
                TestDomBuilders.tradeJson(
                    timestamp = "2023-01-03",
                    symbol = Asset.LTC,
                    side = OrderSide.BUY.name,
                    volume = 5.0,
                    usdAmount = 350.0,
                    success = false,
                    dryRun = false
                )
            )

            renderTradeTable(trades)
            val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
            tbody.rows.length shouldBe 3
            tbody.innerHTML shouldContain "${Asset.BTC}/$USD"
            tbody.innerHTML shouldContain "${Asset.ETH}/$USD"
            tbody.innerHTML shouldContain "${Asset.LTC}/$USD"
            tbody.innerHTML shouldContain "DRY RUN"
            tbody.innerHTML shouldContain "FAILED"

            (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as HTMLInputElement).checked = false
            renderTradeTable(trades)
            tbody.rows.length shouldBe 2
            tbody.innerHTML shouldContain "${Asset.BTC}/$USD"
            tbody.innerHTML shouldContain "${Asset.LTC}/$USD"
            tbody.innerHTML shouldNotContain "${Asset.ETH}/$USD"

            renderTradeTable(emptyArray())
            tbody.rows.length shouldBe 1
            tbody.innerHTML shouldContain "No trades found"
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "updateStats formats each displayed value" {
        val container = document.createElement(DIV)
        container.innerHTML = TestDomBuilders.statsDom()
        document.body!!.appendChild(container)

        try {
            val stats = js("({ allTimeHigh: 15000.5, totalTradesExecuted: 42, totalVolumeTraded: 1000000.0, totalFeesPaid: 250.75 })")
            updateStats(stats)

            document.getElementById(HtmlIds.STAT_ATH)?.textContent shouldBe "$15,000.50"
            document.getElementById(HtmlIds.STAT_TOTAL_TRADES)?.textContent shouldBe "42"
            document.getElementById(HtmlIds.STAT_TOTAL_VOLUME)?.textContent shouldBe "$1,000,000.00"
            document.getElementById(HtmlIds.STAT_TOTAL_FEES)?.textContent shouldBe "$250.75"
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "registerHistoryGlobals exposes chart defaults" {
        registerHistoryGlobals()
        (window.asDynamic().chartDefaults != null) shouldBe true
    }

        "chart builders create charts and preserve visibility" {
        val container = document.createElement(DIV)
        container.innerHTML = TestDomBuilders.chartsDom()
        document.body!!.appendChild(container)
        js("""
            window.chartConfigs = [];
            window.Chart = function(_, config) {
                this.data = config.data;
                this.destroy = function() { this.destroyed = true; };
                this.isDatasetVisible = function(index) { return index === 0; };
                window.chartConfigs.push(config);
            };
        """)

        try {
            registerHistoryGlobals()
            val snapshots = arrayOf(
                json(
                    "timestamp" to "2023-01-01",
                    "totalValue$USD" to 100,
                    "assets" to json(
                        Asset.BTC to json(
                            "value$USD" to 60,
                            "balance" to 2,
                            "currentPercent" to 60
                        ),
                        Asset.USD to json(
                            "value$USD" to 40,
                            "balance" to 40,
                            "currentPercent" to 40
                        )
                    )
                ),
                json(
                    "timestamp" to "2023-01-02",
                    "totalValue$USD" to "invalid",
                    "assets" to json(Asset.BTC to json(
                        "value$USD" to 80,
                        "balance" to 3,
                        "currentPercent" to 80
                    ))
                )
            )
            val trades = arrayOf(
                json(
                    "timestamp" to "2023-01-01",
                    "success" to true,
                    "dryRun" to false,
                    "side" to OrderSide.BUY.name,
                    "usdAmount" to 10
                )
            )

            buildPortfolioValueChart(emptyArray())
            buildAssetHoldingsChart(emptyArray())
            buildAllocationDriftChart(emptyArray())
            buildCumulativePLChart(emptyArray())
            buildPortfolioValueChart(snapshots)
            buildAssetHoldingsChart(snapshots)
            buildAllocationDriftChart(snapshots)
            buildCumulativePLChart(trades)
            buildPortfolioValueChart(snapshots)

            (window.asDynamic().chartConfigs.length as Int) shouldBe 5
            val portfolioConfig = window.asDynamic().chartConfigs[0]
            portfolioConfig.data.datasets.length as Int shouldBe 2
            val updatedPortfolioConfig = window.asDynamic().chartConfigs[4]
            (updatedPortfolioConfig.data.datasets[1].hidden as Boolean) shouldBe true
            createOrUpdate(
                "missing-chart",
                createLineChartConfig(emptyArray(), getClonedChartOptions())
            )
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "loadAll and checkSyncProgress update history content" {
        val container = document.createElement(DIV)
        container.innerHTML = TestDomBuilders.historyDom()
        document.body!!.appendChild(container)
        js("""
            window.Chart = function(_, config) { this.data = config.data; this.destroy = function() {}; this.isDatasetVisible = function() { return true; }; };
            window.fetch = function(url) {
                var data = url.indexOf('snapshots') >= 0
                    ? [{ timestamp: '2023-01-01', totalValue$USD: 100, assets: { BTC: { valueUSD: 100, balance: 1, currentPercent: 100 } } }]
                    : url.indexOf('trades') >= 0
                        ? [{ timestamp: '2023-01-01', symbol: 'BTC', success: true, dryRun: false, side: 'BUY', volume: 1, usdAmount: 100 }]
                        : url.indexOf('sync-progress') >= 0
                            ? { seeded: false, offset: 5, total: 10 }
                            : { allTimeHigh: 100, totalTradesExecuted: 1, totalVolumeTraded: 100, totalFeesPaid: 1 };
                return Promise.resolve({ json: function() { return Promise.resolve(data); } });
            };
        """)
        registerHistoryGlobals()

        try {
            checkSyncProgress().await() shouldBe false
            (document.getElementById(HtmlIds.SYNC_PROGRESS_BAR) as HTMLElement).style.width shouldBe "50%"
            loadAll(TimeRange.TWENTY_FOUR_HOURS.key).await()
            (window.asDynamic().chartDefaults.scales.x.time.unit as String) shouldBe "hour"
            loadAll(TimeRange.ALL.key).await()
            (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "checkSyncProgress hides the banner when history is seeded" {
        val container = document.createElement(DIV)
        container.innerHTML = TestDomBuilders.syncProgressDom()
        document.body!!.appendChild(container)
        js("""
            window.fetch = function() {
                return Promise.resolve({ json: function() { return Promise.resolve({ seeded: true }); } });
            };
        """)
        try {
            checkSyncProgress().await() shouldBe true
            (document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) as HTMLElement).style.display shouldBe "none"
        } finally {
            document.body!!.removeChild(container)
        }
    }
    }
}
