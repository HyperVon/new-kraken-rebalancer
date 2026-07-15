package com.gemini.krakenbot.frontend

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.*
import kotlin.test.assertEquals

@Suppress("unused")
class HistoryTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "formatUSD renders currency amounts" {
        formatUSD(1234.56) shouldBe "$1,234.56"
        formatUSD(0.0) shouldBe "$0.00"
        formatUSD(-12.3456) shouldBe "$-12.35"
    }

        "formatPair handles valid and missing symbols" {
        val trade1: dynamic = js("({ symbol: 'BTC' })")
        formatPair(trade1) shouldBe "BTC/USD"

        val trade2: dynamic = js("({ symbol: null })")
        formatPair(trade2) shouldBe ""

        val trade3: dynamic = js("({})")
        formatPair(trade3) shouldBe ""

        formatPair(null) shouldBe ""
    }

        "getUniqueSymbols filters and sorts symbols" {
        val snapshots = arrayOf(
            js("({ assets: { BTC: {}, ETH: {}, USD: {} } })"),
            js("({ assets: { BTC: {}, SOL: {}, USD: {} } })"),
            js("({ assets: null })")
        )

        val symbolsExcludeUsd = getUniqueSymbols(snapshots, excludeUsd = true)
        symbolsExcludeUsd shouldBe listOf("BTC", "ETH", "SOL")

        val symbolsIncludeUsd = getUniqueSymbols(snapshots, excludeUsd = false)
        symbolsIncludeUsd shouldBe listOf("BTC", "ETH", "SOL", "USD")
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
            js("({ timestamp: '2023-01-01T10:00:00Z', success: true, dryRun: false, side: 'BUY', usdAmount: 100.0 })"),
            js("({ timestamp: '2023-01-01T08:00:00Z', success: true, dryRun: false, side: 'SELL', usdAmount: 50.0 })"),
            js("({ timestamp: '2023-01-01T09:00:00Z', success: false, dryRun: false, side: 'BUY', usdAmount: 200.0 })"),
            js("({ timestamp: '2023-01-01T11:00:00Z', success: true, dryRun: true, side: 'BUY', usdAmount: 300.0 })"),
            js("({ timestamp: '2023-01-01T12:00:00Z', success: true, dryRun: false, side: 'SELL', usdAmount: 80.0 })")
        )

        val result = calculateCumulativePL(trades)

        result.size shouldBe 3
        val r0 = result[0]
        val r0y = r0.y
        assertEquals(50.0, r0y.toString().toDouble())
    }

        "renderTradeTable filters dry runs and displays empty states" {
        val container = document.createElement("div")
        container.innerHTML = """
            <input type="checkbox" id="show-dry-run-checkbox" checked>
            <table><tbody id="trade-table-body"></tbody></table>
        """.trimIndent()
        document.body!!.appendChild(container)

        try {
            val trades = arrayOf(
                js("({ timestamp: '2023-01-01', symbol: 'BTC', side: 'BUY', volume: 0.1, usdAmount: 2000.0, success: true, dryRun: false })"),
                js("({ timestamp: '2023-01-02', symbol: 'ETH', side: 'SELL', volume: 1.0, usdAmount: 1800.0, success: true, dryRun: true })"),
                js("({ timestamp: '2023-01-03', symbol: 'LTC', side: 'BUY', volume: 5.0, usdAmount: 350.0, success: false, dryRun: false })")
            )

            renderTradeTable(trades)
            val tbody = document.getElementById("trade-table-body") as HTMLTableSectionElement
            tbody.rows.length shouldBe 3
            tbody.innerHTML shouldContain "BTC/USD"
            tbody.innerHTML shouldContain "ETH/USD"
            tbody.innerHTML shouldContain "LTC/USD"
            tbody.innerHTML shouldContain "DRY RUN"
            tbody.innerHTML shouldContain "FAILED"

            (document.getElementById("show-dry-run-checkbox") as HTMLInputElement).checked = false
            renderTradeTable(trades)
            tbody.rows.length shouldBe 2
            tbody.innerHTML shouldContain "BTC/USD"
            tbody.innerHTML shouldContain "LTC/USD"
            tbody.innerHTML shouldNotContain "ETH/USD"

            renderTradeTable(emptyArray())
            tbody.rows.length shouldBe 1
            tbody.innerHTML shouldContain "No trades found"
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "updateStats formats each displayed value" {
        val container = document.createElement("div")
        container.innerHTML = """
            <div id="stat-ath"></div>
            <div id="stat-total-trades"></div>
            <div id="stat-total-volume"></div>
            <div id="stat-total-fees"></div>
        """.trimIndent()
        document.body!!.appendChild(container)

        try {
            val stats = js("({ allTimeHigh: 15000.5, totalTradesExecuted: 42, totalVolumeTraded: 1000000.0, totalFeesPaid: 250.75 })")
            updateStats(stats)

            document.getElementById("stat-ath")?.textContent shouldBe "$15,000.50"
            document.getElementById("stat-total-trades")?.textContent shouldBe "42"
            document.getElementById("stat-total-volume")?.textContent shouldBe "$1,000,000.00"
            document.getElementById("stat-total-fees")?.textContent shouldBe "$250.75"
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "registerHistoryGlobals exposes chart defaults" {
        registerHistoryGlobals()
        (window.asDynamic().chartDefaults != null) shouldBe true
    }

        "chart builders create charts and preserve visibility" {
        val container = document.createElement("div")
        container.innerHTML = """
            <canvas id="portfolio-value-chart"></canvas>
            <canvas id="asset-holdings-chart"></canvas>
            <canvas id="allocation-drift-chart"></canvas>
            <canvas id="cumulative-pl-chart"></canvas>
        """.trimIndent()
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
                js("({ timestamp: '2023-01-01', totalValueUSD: 100, assets: { BTC: { valueUSD: 60, balance: 2, currentPercent: 60 }, USD: { valueUSD: 40, balance: 40, currentPercent: 40 } } })"),
                js("({ timestamp: '2023-01-02', totalValueUSD: 'invalid', assets: { BTC: { valueUSD: 80, balance: 3, currentPercent: 80 } } })")
            )
            val trades = arrayOf(
                js("({ timestamp: '2023-01-01', success: true, dryRun: false, side: 'BUY', usdAmount: 10 })")
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
            createOrUpdate("missing-chart", createLineChartConfig(emptyArray(), getClonedChartOptions()))
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "loadAll and checkSyncProgress update history content" {
        val container = document.createElement("div")
        container.innerHTML = """
            <canvas id="portfolio-value-chart"></canvas><canvas id="asset-holdings-chart"></canvas>
            <canvas id="allocation-drift-chart"></canvas><canvas id="cumulative-pl-chart"></canvas>
            <table><tbody id="trade-table-body"></tbody></table><input id="show-dry-run-checkbox" type="checkbox" checked>
            <div id="stat-ath"></div><div id="stat-total-trades"></div><div id="stat-total-volume"></div><div id="stat-total-fees"></div>
            <div id="sync-progress-banner"></div><div id="sync-progress-bar"></div><div id="sync-progress-text"></div>
        """.trimIndent()
        document.body!!.appendChild(container)
        js("""
            window.Chart = function(_, config) { this.data = config.data; this.destroy = function() {}; this.isDatasetVisible = function() { return true; }; };
            window.fetch = function(url) {
                var data = url.indexOf('snapshots') >= 0
                    ? [{ timestamp: '2023-01-01', totalValueUSD: 100, assets: { BTC: { valueUSD: 100, balance: 1, currentPercent: 100 } } }]
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
            (document.getElementById("sync-progress-bar") as HTMLElement).style.width shouldBe "50%"
            loadAll("24h").await()
            (window.asDynamic().chartDefaults.scales.x.time.unit as String) shouldBe "hour"
            loadAll("all").await()
            (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "checkSyncProgress hides the banner when history is seeded" {
        val container = document.createElement("div")
        container.innerHTML = "<div id=\"sync-progress-banner\"></div>"
        document.body!!.appendChild(container)
        js("""
            window.fetch = function() {
                return Promise.resolve({ json: function() { return Promise.resolve({ seeded: true }); } });
            };
        """)
        try {
            checkSyncProgress().await() shouldBe true
            (document.getElementById("sync-progress-banner") as HTMLElement).style.display shouldBe "none"
        } finally {
            document.body!!.removeChild(container)
        }
    }
    }
}
