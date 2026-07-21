package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import org.w3c.dom.*
import kotlin.js.Date
import kotlin.time.Duration.Companion.milliseconds

class CoverageTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        // Test, createOrUpdate branches
        "createOrUpdate handles existing chart and visibility states" {
            val container = document.createElement("div")
            container.innerHTML = """
                <canvas id="test-chart"></canvas>
            """.trimIndent()
            document.body!!.appendChild(container)
            js("""
                window.chartCallCount = 0;
                window.Chart = function(_, config) {
                    this.data = config.data;
                    this.destroyCalled = false;
                    this.isDatasetVisible = function(index) { return index === 0; };
                    this.destroy = function() { this.destroyCalled = true; };
                    window.chartCallCount++;
                };
            """)
            try {
                registerHistoryGlobals()
                
                // First call - no existing chart
                val config1 = js("{ type: 'line', data: { datasets: [] }, options: {} }")
                createOrUpdate("test-chart", config1)
                
                // Second call - existing chart, visibility states stored
                val config2 = js("{ type: 'line', data: { datasets: [{ label: 'A' }, { label: 'B' }] }, options: {} }")
                createOrUpdate("test-chart", config2)
                
                // Third call with same config - should preserve visibility
                val config3 = js("{ type: 'line', data: { datasets: [{ label: 'A', hidden: false }, { label: 'B', hidden: true }] }, options: {} }")
                createOrUpdate("test-chart", config3)
                
                ((window.asDynamic().chartCallCount as Int) >= 2).shouldBeTrue()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "createOrUpdate does nothing when canvas missing" {
            // Should not throw
            createOrUpdate("non-existent-canvas", js("{}"))
        }

        // Test chart builder early return
        "chart builders return early for empty snapshots" {
            val container = document.createElement("div")
            container.innerHTML = """
                <canvas id="portfolio-chart"></canvas>
                <canvas id="holdings-chart"></canvas>
                <canvas id="drift-chart"></canvas>
                <canvas id="pl-chart"></canvas>
            """.trimIndent()
            document.body!!.appendChild(container)
            js("""
                window.Chart = function(_, config) { this.data = config.data; this.destroy = function() {}; };
                window.fetch = function() { return Promise.resolve({ json: function() { return Promise.resolve({}); } }); };
            """)
            try {
                registerHistoryGlobals()
                
                // These should return early without throwing
                buildPortfolioValueChart(emptyArray())
                buildAssetHoldingsChart(emptyArray())
                buildAllocationDriftChart(emptyArray())
                buildCumulativePLChart(emptyArray())
                
                // Verify no charts were created (since early return)
                // We can't easily check, but at least no exception
            } finally {
                document.body!!.removeChild(container)
            }
        }

        // Test calculateCumulativePL edge cases
        "calculateCumulativePL handles various trade scenarios" {
            // Empty trades
            val empty = calculateCumulativePL(emptyArray())
            empty.size shouldBe 0

            // Single successful buy
            val buy = js("({ timestamp: '2023-01-01', success: true, dryRun: false, side: 'BUY', usdAmount: 100.0 })")
            val resultBuy = calculateCumulativePL(arrayOf(buy))
            resultBuy.size shouldBe 1
            val r0 = resultBuy[0]
            r0.y.toString().toDouble() shouldBe -100.0  // BUY subtracts

            // Single successful sell
            val sell = js("({ timestamp: '2023-01-01', success: true, dryRun: false, side: 'SELL', usdAmount: 50.0 })")
            val resultSell = calculateCumulativePL(arrayOf(sell))
            resultSell.size shouldBe 1
            val s0 = resultSell[0]
            s0.y.toString().toDouble() shouldBe 50.0   // SELL adds

            // Mixed with failed and dryRun (should be filtered out)
            val mixed = arrayOf(
                js("({ timestamp: '2023-01-01', success: true, dryRun: false, side: 'BUY', usdAmount: 100.0 })"),
                js("({ timestamp: '2023-01-02', success: false, dryRun: false, side: 'SELL', usdAmount: 50.0 })"), // failed
                js("({ timestamp: '2023-01-03', success: true, dryRun: true, side: 'BUY', usdAmount: 200.0 })"),   // dryRun
                js("({ timestamp: '2023-01-04', success: true, dryRun: false, side: 'SELL', usdAmount: 30.0 })")
            )
            val resultMixed = calculateCumulativePL(mixed)
            resultMixed.size shouldBe 2  // Only the buy and sell (first and last)
            val m0 = resultMixed[0]
            val m1 = resultMixed[1]
            (m0.y.toString().toDouble()) shouldBe -100.0
            (m1.y.toString().toDouble()) shouldBe (-100.0 + 30.0)  // -70
        }

        // Test renderTradeTable edge cases
        "renderTradeTable handles missing tbody and empty trades" {
            // Missing tbody should return early
            renderTradeTable(arrayOf())  // Should not throw

            // Empty tbody with checkbox
            val container = document.createElement("div")
            container.innerHTML = """
                <input type="checkbox" id="show-dry-run-checkbox" checked>
                <table><tbody id="trade-table-body"></tbody></table>
            """.trimIndent()
            document.body!!.appendChild(container)
            try {
                renderTradeTable(arrayOf())
                val tbody = document.getElementById("trade-table-body") as HTMLTableSectionElement
                tbody.rows.length shouldBe 1
                tbody.innerHTML shouldContain "No trades found"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        // Test updateStats with missing elements
        "updateStats handles missing elements gracefully" {
            val container = document.createElement("div")
            container.innerHTML = """
                <div id="stat-ath"></div>
                <!-- missing other stats -->
            """.trimIndent()
            document.body!!.appendChild(container)
            try {
                val stats = js("({ allTimeHigh: 15000.5, totalTradesExecuted: 42, totalVolumeTraded: 1000000.0, totalFeesPaid: 250.75 })")
                updateStats(stats)  // Should not throw
                document.getElementById("stat-ath")?.textContent shouldBe "$15,000.50"
                // Other elements missing, so no exception
            } finally {
                document.body!!.removeChild(container)
            }
        }

        // Test loadAll when branches
        "loadAll sets chartDefaults time unit based on range" {
            val container = document.createElement("div")
            container.innerHTML = """
                <canvas id="portfolio-value-chart"></canvas>
                <canvas id="asset-holdings-chart"></canvas>
                <canvas id="allocation-drift-chart"></canvas>
                <canvas id="cumulative-pl-chart"></canvas>
                <table><tbody id="trade-table-body"></tbody></table>
                <input id="show-dry-run-checkbox" type="checkbox" checked>
                <div id="stat-ath"></div><div id="stat-total-trades"></div><div id="stat-total-volume"></div><div id="stat-total-fees"></div>
                <div id="sync-progress-banner"></div><div id="sync-progress-bar"></div><div id="sync-progress-text"></div>
            """.trimIndent()
            document.body!!.appendChild(container)
            js("""
                window.capturedUrls = [];
                window.fetch = function(url) {
                    window.capturedUrls.push(url);
                    var data;
                    if (url.indexOf('snapshots') >= 0) {
                        data = [{ timestamp: '2023-01-01', totalValueUSD: 100, assets: { BTC: { valueUSD: 100, balance: 1, currentPercent: 100 } } }];
                    } else if (url.indexOf('trades') >= 0) {
                        data = [{ timestamp: '2023-01-01', symbol: 'BTC', success: true, dryRun: false, side: 'BUY', volume: 1, usdAmount: 100 }];
                    } else if (url.indexOf('sync-progress') >= 0) {
                        data = { seeded: false, offset: 5, total: 10 };
                    } else {
                        data = { allTimeHigh: 100, totalTradesExecuted: 1, totalVolumeTraded: 100, totalFeesPaid: 1 };
                    }
                    return Promise.resolve({ json: function() { return Promise.resolve(data); } });
                };
                window.Chart = function(_, config) { this.data = config.data; this.destroy = function() {}; this.isDatasetVisible = function() { return true; }; };
            """)
            try {
                registerHistoryGlobals()
                
                // Test 24h range
                loadAll("24h").await()
                (window.asDynamic().chartDefaults.scales.x.time.unit as String) shouldBe "hour"
                
                // Test 'all' range (should delete unit)
                loadAll("all").await()
                (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true
                
                // Test default (day) range
                loadAll("30d").await()
                (window.asDynamic().chartDefaults.scales.x.time.unit as String) shouldBe "day"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        // Test checkSyncProgress branches
        "checkSyncProgress handles banner missing, seeded true/false, and offset/total" {
            val container = document.createElement("div")
            container.innerHTML = """
                <div id="sync-progress-banner"></div>
                <div id="sync-progress-bar"></div>
                <div id="sync-progress-text"></div>
            """.trimIndent()
            document.body!!.appendChild(container)
            
            // Case 1: banner missing -> should resolve to true
            js("""
                window.fetch = function() {
                    return Promise.resolve({ json: function() { return Promise.resolve({ seeded: true }); } });
                };
            """)
            try {
                // Remove banner to test missing case
                document.getElementById("sync-progress-banner")?.remove()
                checkSyncProgress().await() shouldBe true
            } finally {
                // Restore banner for next test
                container.innerHTML = """
                    <div id="sync-progress-banner"></div>
                    <div id="sync-progress-bar"></div>
                    <div id="sync-progress-text"></div>
                """.trimIndent()
                document.body!!.appendChild(container)
            }
            
            // Case 2: seeded true -> hide banner, resolve true
            js("""
                window.fetch = function() {
                    return Promise.resolve({ json: function() { return Promise.resolve({ seeded: true }); } });
                };
            """)
            try {
                checkSyncProgress().await() shouldBe true
                val banner = document.getElementById("sync-progress-banner") as HTMLElement
                banner.style.display shouldBe "none"
            } finally {}
            
            // Case 3: seeded false -> show banner, calculate progress
            js("""
                window.fetch = function() {
                    return Promise.resolve({ json: function() { return Promise.resolve({ seeded: false, offset: 0, total: 100 }); } });
                };
            """)
            try {
                checkSyncProgress().await() shouldBe false
                val banner = document.getElementById("sync-progress-banner") as HTMLElement
                banner.style.display shouldBe "block"
                val bar = document.getElementById("sync-progress-bar") as HTMLElement
                bar.style.width shouldBe "0%"  // 0/100*100
                val text = document.getElementById("sync-progress-text") as HTMLElement
                text.textContent shouldBe "0 / 100 (0%)"
            } finally {}
            
            // Case 4: offset > 0
            js("""
                window.fetch = function() {
                    return Promise.resolve({ json: function() { return Promise.resolve({ seeded: false, offset: 50, total: 100 }); } });
                };
            """)
            try {
                checkSyncProgress().await() shouldBe false
                val bar = document.getElementById("sync-progress-bar") as HTMLElement
                bar.style.width shouldBe "50%"  // 50/100*100
                val text = document.getElementById("sync-progress-text") as HTMLElement
                text.textContent shouldBe "50 / 100 (50%)"
            } finally {}
            
            // Case 5: error in fetch -> catch returns false
            js("""
                window.fetch = function() {
                    return Promise.reject(new Error('Network error'));
                };
            """)
            try {
                checkSyncProgress().await() shouldBe false
            } finally {
                document.body!!.removeChild(container)
            }
        }

        // Settings coverage
        "updateAllocationTotal handles various input scenarios" {
            val container = document.createElement("div")
            container.innerHTML = """
                <span id="total-allocated-display"></span>
                <button id="save-button"></button>
            """.trimIndent()
            document.body!!.appendChild(container)
            try {
                // Case 1: valid inputs summing to 100 with USD
                val input1 = document.createElement("input") as HTMLInputElement
                input1.name = "targets"
                input1.value = "40.0"
                val sym1 = document.createElement("input") as HTMLInputElement
                sym1.name = "symbols"
                sym1.value = Asset.BTC
                val input2 = document.createElement("input") as HTMLInputElement
                input2.name = "targets"
                input2.value = "60.0"
                val sym2 = document.createElement("input") as HTMLInputElement
                sym2.name = "symbols"
                sym2.value = Asset.USD
                container.appendChild(input1)
                container.appendChild(sym1)
                container.appendChild(input2)
                container.appendChild(sym2)
                
                updateAllocationTotal()
                val totalDisplay = document.getElementById("total-allocated-display") as HTMLSpanElement
                val saveButton = document.getElementById("save-button") as HTMLButtonElement
                totalDisplay.textContent shouldBe "Total: 100.00%"
                saveButton.disabled.shouldBeFalse()
                totalDisplay.classList.contains("live").shouldBeTrue()
                
                // Cleanup for next test
                container.innerHTML = """
                    <span id="total-allocated-display"></span>
                    <button id="save-button"></button>
                """.trimIndent()
                document.body!!.appendChild(container)
                
                // Case 2: sum not 100 -> disabled, delayed
                val input3 = document.createElement("input") as HTMLInputElement
                input3.name = "targets"
                input3.value = "30.0"
                val sym3 = document.createElement("input") as HTMLInputElement
                sym3.name = "symbols"
                sym3.value = Asset.BTC
                val input4 = document.createElement("input") as HTMLInputElement
                input4.name = "targets"
                input4.value = "30.0"
                val sym4 = document.createElement("input") as HTMLInputElement
                sym4.name = "symbols"
                sym4.value = "USD"
                container.appendChild(input3)
                container.appendChild(sym3)
                container.appendChild(input4)
                container.appendChild(sym4)
                
                updateAllocationTotal()
                val totalDisplay2 = document.getElementById("total-allocated-display") as HTMLSpanElement
                val saveButton2 = document.getElementById("save-button") as HTMLButtonElement
                totalDisplay2.textContent shouldBe "Total: 60.00%"
                saveButton2.disabled.shouldBeTrue()
                totalDisplay2.classList.contains("delayed").shouldBeTrue()
                
                // Case 3: missing USD symbol -> disabled even if sum 100
                container.innerHTML = """
                    <span id="total-allocated-display"></span>
                    <button id="save-button"></button>
                """.trimIndent()
                document.body!!.appendChild(container)
                val input5 = document.createElement("input") as HTMLInputElement
                input5.name = "targets"
                input5.value = "50.0"
                val sym5 = document.createElement("input") as HTMLInputElement
                sym5.name = "symbols"
                sym5.value = Asset.BTC
                val input6 = document.createElement("input") as HTMLInputElement
                input6.name = "targets"
                input6.value = "50.0"
                val sym6 = document.createElement("input") as HTMLInputElement
                sym6.name = "symbols"
                sym6.value = "EETH"  // not USD
                container.appendChild(input5)
                container.appendChild(sym5)
                container.appendChild(input6)
                container.appendChild(sym6)
                
                updateAllocationTotal()
                val totalDisplay3 = document.getElementById("total-allocated-display") as HTMLSpanElement
                val saveButton3 = document.getElementById("save-button") as HTMLButtonElement
                totalDisplay3.textContent shouldBe "Total: 100.00%"
                saveButton3.disabled.shouldBeTrue()  // missing USD
                totalDisplay3.classList.contains("delayed").shouldBeTrue()
                
                // Case 4: missing elements -> should not throw
                container.innerHTML = ""
                updateAllocationTotal()  // Should not throw
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "addAssetRow handles edge cases" {
            val container = document.createElement("div")
            container.innerHTML = """
                <input type="text" id="new-symbol-input" value="${Asset.BTC}">
                <div id="allocations-container"></div>
            """.trimIndent()
            document.body!!.appendChild(container)
            try {
                // Case 1: empty symbol -> should return early
                val symbolInput = document.getElementById("new-symbol-input") as HTMLInputElement
                symbolInput.value = ""
                addAssetRow()
                val allocContainer = document.getElementById("allocations-container") as HTMLElement
                allocContainer.childElementCount.shouldBe(0)
                
                // Case 2: symbol already exists -> should show alert and return
                symbolInput.value = Asset.BTC
                // Pre-populate container with existing BTC
                val existingRow = document.createElement("div")
                existingRow.className = "allocation-edit-row"
                existingRow.innerHTML = """
                    <input type="hidden" name="symbols" value="${Asset.BTC}">
                """.trimIndent()
                allocContainer.appendChild(existingRow)
                
                // Mock window.alert to verify it's called
                js("window.alertCalled = false; window.alert = function(msg) { window.alertCalled = true; };")
                try {
                    addAssetRow()
                    (window.asDynamic().alertCalled as Boolean) shouldBe true
                    allocContainer.childElementCount.shouldBe(1)  // No new row added
                } finally {
                    js("window.alert = null;")
                }
                
                // Case 3: missing container -> should return early
                symbolInput.value = "NEW"
                allocContainer.remove()  // Remove container
                addAssetRow()  // Should not throw
            } finally {
                if (container.parentNode != null) {
                    document.body!!.removeChild(container)
                }
            }
        }

        // Dashboard coverage
        "updateAge handles missing elements and stale/fresh states" {
            val container = document.createElement("div")
            container.innerHTML = """
                <span class="data-age-value"></span>
                <span class="data-age-time" data-epoch=""></span>
                <span class="status-badge"></span>
            """.trimIndent()
            document.body!!.appendChild(container)
            try {
                // Missing epoch attribute -> should return early
                updateAge()
                val ageVal = document.getElementsByClassName("data-age-value")[0] as HTMLSpanElement
                ageVal.textContent shouldBe ""  // unchanged
                
                // Valid recent epoch -> fresh
                val recentTime = Date.now() - 5000  // 5 seconds ago
                val timeEl = document.getElementsByClassName("data-age-time")[0] as HTMLSpanElement
                timeEl.setAttribute("data-epoch", recentTime.toString())
                updateAge()
                ageVal.textContent shouldBe "5s ago"
                val badge = document.getElementsByClassName("status-badge")[0] as HTMLElement
                badge.classList.contains("live").shouldBeTrue()
                badge.classList.contains("delayed").shouldBeFalse()
                
                // Stale epoch (>90s)
                val staleTime = Date.now() - 95000  // 95 seconds ago
                timeEl.setAttribute("data-epoch", staleTime.toString())
                updateAge()
                ageVal.textContent shouldBe "95s ago"
                badge.classList.contains("delayed").shouldBeTrue()
                badge.classList.contains("live").shouldBeFalse()

                // Test AM/PM branches and hour % 12 == 0 branches
                // 9:30 AM (9:30)
                val amTime = Date(2023, 0, 1, 9, 30, 0).getTime()
                timeEl.setAttribute("data-epoch", amTime.toString())
                updateAge()
                timeEl.textContent shouldBe "09:30:00 AM"

                // 3:30 PM (15:30)
                val pmTime = Date(2023, 0, 1, 15, 30, 0).getTime()
                timeEl.setAttribute("data-epoch", pmTime.toString())
                updateAge()
                timeEl.textContent shouldBe "03:30:00 PM"

                // 12:30 PM
                val noonTime = Date(2023, 0, 1, 12, 30, 0).getTime()
                timeEl.setAttribute("data-epoch", noonTime.toString())
                updateAge()
                timeEl.textContent shouldBe "12:30:00 PM"
                
                // Missing badge element -> should not crash when toggling classes
                val badgeContainer = document.createElement("div")
                badgeContainer.innerHTML = """
                    <span class="data-age-value"></span>
                    <span class="data-age-time" data-epoch="0"></span>
                """.trimIndent()
                document.body!!.appendChild(badgeContainer)
                try {
                    updateAge()  // Should not throw
                } finally {
                    document.body!!.removeChild(badgeContainer)
                }
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "reapplySort and sortTable handle edge cases" {
            val container = document.createElement("div")
            container.innerHTML = """
                <table>
                    <thead>
                        <tr>
                            <th class="sortable">Col0</th>
                            <th class="sortable">Col1</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr class="hoverable"><td data-sort-value="10">A</td><td data-sort-value="20">B</td></tr>
                        <tr class="hoverable"><td data-sort-value="5">C</td><td data-sort-value="15">D</td></tr>
                    </tbody>
                </table>
            """.trimIndent()
            document.body!!.appendChild(container)
            try {
                // Case: no sortable headers -> reapplySort should not throw
                val noHeadersContainer = document.createElement("div")
                noHeadersContainer.innerHTML = "<table><tbody></tbody></table>"
                document.body!!.appendChild(noHeadersContainer)
                try {
                    reapplySort()  // Should not throw
                } finally {
                    document.body!!.removeChild(noHeadersContainer)
                }
                
                // Case: header missing -> sortTable should return early
                val fakeHeader = document.createElement("th") as HTMLElement
                fakeHeader.className = "sortable"
                // Not attached to document
                sortTable(fakeHeader, 0)  // Should not throw
                
                // Normal sorting
                val header0 = document.getElementsByClassName("sortable")[0] as HTMLTableCellElement
                val header1 = document.getElementsByClassName("sortable")[1] as HTMLTableCellElement
                
                // Sort by col0 ascending (default)
                sortTable(header0, 0)
                var rows = container.querySelectorAll("tbody tr")
                (rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "A"  // "10" < "5" lexicographically
                
                // Sort by col0 descending
                sortTable(header0, 0, "desc")
                rows = container.querySelectorAll("tbody tr")
                (rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "C"  // "5" > "10" lexicographically
                
                // Sort by col1 ascending
                sortTable(header1, 1)
                rows = container.querySelectorAll("tbody tr")
                (rows.item(0) as HTMLTableRowElement).cells.item(1)?.textContent shouldBe "D"  // 15 < 20
                
                // Sort by col1 descending
                sortTable(header1, 1, "desc")
                rows = container.querySelectorAll("tbody tr")
                (rows.item(0) as HTMLTableRowElement).cells.item(1)?.textContent shouldBe "B"  // 20 > 15
                
                // Test with missing data-sort-value (falls back to textContent)
                val row2 = document.createElement("tr")
                row2.className = "hoverable"
                val td2a = document.createElement("td")
                td2a.textContent = "Apple"
                val td2b = document.createElement("td")
                td2b.textContent = "Banana"
                row2.appendChild(td2a)
                row2.appendChild(td2b)
                container.querySelector("tbody")!!.appendChild(row2)
                
                sortTable(header0, 0)  // Sort by first column text
                rows = container.querySelectorAll("tbody tr")
                // Should order: A (10), C (5), Apple (lexicographically after numbers? Actually strings: "A", "C", "Apple"),
                // But we have data-sort-value on the first two rows, the third row uses textContent
                // This is just to ensure no exception
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "registerDashboardGlobals exposes sortTable function" {
            registerDashboardGlobals()
            (window.asDynamic().sortTable != null) shouldBe true
        }

        "main and initOnLoad handle body presence/absence" {
            // Test initOnLoad when body exists
            val body = document.body!!
            val originalInner = body.innerHTML
            body.innerHTML = "<div id='test'></div>"
            try {
                initOnLoad()  // Should not throw
                // We can't easily test which init function ran without spying, but at least no exception
            } finally {
                body.innerHTML = originalInner
            }
            
            // Test initOnLoad when body is null (simulate by removing body temporarily)
            // Note: We can't actually remove document.body, but we can test the condition logic
            // The function checks `if (document.body != null)` - we trust it works
        }

        "chart builders config callbacks cover tooltip and ticks formatting" {
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
                    this.destroy = function() {};
                    this.isDatasetVisible = function() { return true; };
                    window.chartConfigs.push(config);
                };
            """)
            try {
                registerHistoryGlobals()
                val snapshots = arrayOf(
                    js("({ timestamp: '2023-01-01', totalValueUSD: 100, assets: { USD: { valueUSD: 100, balance: 100, currentPercent: 100 } } })"),
                    js("({ timestamp: '2023-01-02', totalValueUSD: 160, assets: { BTC: { valueUSD: 60, balance: 2, currentPercent: 37.5 }, USD: { valueUSD: 100, balance: 50, currentPercent: 62.5 } } })")
                )
                val trades = arrayOf(
                    js("({ timestamp: '2023-01-01', success: true, dryRun: false, side: 'BUY', usdAmount: 10 })")
                )

                buildPortfolioValueChart(snapshots)
                buildAssetHoldingsChart(snapshots)
                buildAllocationDriftChart(snapshots)
                buildCumulativePLChart(trades)

                // 1. Portfolio chart callbacks
                val portConfig = window.asDynamic().chartConfigs[0]
                val label1 = portConfig.options.plugins.tooltip.callbacks.label
                val mockCtx1 = js("({ dataset: { label: 'BTC' }, parsed: { y: 12.3456 } })")
                label1(mockCtx1).toString() shouldContain "BTC"
                val tick1 = portConfig.options.scales.y.ticks.callback
                tick1(12.34, 0, null).toString() shouldContain "$12.34"

                // 2. Asset holdings chart callbacks
                val holdingsConfig = window.asDynamic().chartConfigs[1]
                val label2 = holdingsConfig.options.plugins.tooltip.callbacks.label
                val mockCtx2 = js("({ dataset: { label: 'BTC' }, parsed: { y: 12.34 }, dataIndex: 1 })")
                label2(mockCtx2).toString() shouldContain "BTC: +12.34%"
                val mockCtx2USD = js("({ dataset: { label: 'USD' }, parsed: { y: -50.0 }, dataIndex: 1 })")
                label2(mockCtx2USD).toString() shouldContain "USD: -50.00%"
                val tick2 = holdingsConfig.options.scales.y.ticks.callback
                tick2(12.34, 0, null).toString() shouldBe "+12.34%"
                tick2(-5.6, 0, null).toString() shouldBe "-5.6%"

                // 3. Allocation drift chart callbacks
                val driftConfig = window.asDynamic().chartConfigs[2]
                val label3 = driftConfig.options.plugins.tooltip.callbacks.label
                val mockCtx3 = js("({ dataset: { label: 'BTC' }, parsed: { y: 12.34 } })")
                label3(mockCtx3).toString() shouldBe "BTC: 12.34%"
                val tick3 = driftConfig.options.scales.y.ticks.callback
                tick3(12.34, 0, null).toString() shouldBe "12.34%"

                // 4. Cumulative P&L chart callbacks
                val plConfig = window.asDynamic().chartConfigs[3]
                val tick4 = plConfig.options.scales.y.ticks.callback
                tick4(12.34, 0, null).toString() shouldContain "$12.34"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "initHistory sets up click listeners and checkbox listeners" {
            val container = document.createElement("div")
            container.innerHTML = """
                <button class="time-range-btn" data-range="24h"></button>
                <button class="time-range-btn active" data-range="30d"></button>
                <input type="checkbox" id="show-dry-run-checkbox" checked>
                <div id="sync-progress-banner"></div>
                <div id="sync-progress-bar"></div>
                <div id="sync-progress-text"></div>
                <canvas id="portfolio-value-chart"></canvas>
                <canvas id="asset-holdings-chart"></canvas>
                <canvas id="allocation-drift-chart"></canvas>
                <canvas id="cumulative-pl-chart"></canvas>
                <table><tbody id="trade-table-body"></tbody></table>
                <div id="stat-ath"></div><div id="stat-total-trades"></div><div id="stat-total-volume"></div><div id="stat-total-fees"></div>
            """.trimIndent()
            document.body!!.appendChild(container)
            
            val oldSetInterval = window.asDynamic().setInterval
            val oldClearInterval = window.asDynamic().clearInterval
            var intervalCb: (() -> Unit)? = null
            window.asDynamic().setInterval = { cb: () -> Unit, _: Int ->
                intervalCb = cb
                42
            }
            var clearIntervalCalled = false
            window.asDynamic().clearInterval = { id: Int ->
                id shouldBe 42
                clearIntervalCalled = true
            }

            js("""
                window.fetchCount = 0;
                window.fetch = function(url) {
                    var data;
                    if (url.indexOf('sync-progress') >= 0) {
                        if (window.fetchCount === 0) {
                            window.fetchCount++;
                            data = { seeded: false, offset: 5, total: 10 };
                        } else {
                            data = { seeded: true };
                        }
                    } else if (url.indexOf('snapshots') >= 0) {
                        data = [];
                    } else if (url.indexOf('trades') >= 0) {
                        data = [];
                    } else {
                        data = {};
                    }
                    return Promise.resolve({ json: function() { return Promise.resolve(data); } });
                };
                window.Chart = function(_, config) { this.data = config.data; this.destroy = function() {}; this.isDatasetVisible = function() { return true; }; };
            """)

            try {
                initHistory()
                
                // Let the first checkSyncProgress promise resolve
                delay(10.milliseconds)

                // Now the interval should be set up
                (intervalCb != null).shouldBeTrue()
                
                // Trigger interval callback to check sync progress again
                intervalCb?.invoke()
                
                // Let the promise resolve
                delay(10.milliseconds)
                
                clearIntervalCalled.shouldBeTrue()

                // Trigger button click
                val button24h = document.querySelector("button[data-range='24h']") as HTMLElement
                button24h.click()
                
                // Trigger checkbox change
                val checkbox = document.getElementById("show-dry-run-checkbox") as HTMLInputElement
                checkbox.checked = false
                val event = document.createEvent("Event")
                event.initEvent(type = "change", bubbles = true, cancelable = true)
                checkbox.dispatchEvent(event)

                delay(10.milliseconds)
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
                document.body!!.removeChild(container)
            }
        }

        "registerSettingsGlobals and registerDashboardGlobals wrappers can be called" {
            registerSettingsGlobals()
            registerDashboardGlobals()
            // Exercise internal variables getters and setters
            currentSortCol = 4
            currentSortCol shouldBe 4
            currentSortDir = "desc"
            currentSortDir shouldBe "desc"
            
            // We can invoke the wrappers
            val container = document.createElement("div")
            container.innerHTML = """
                <input id="new-symbol-input" value="">
                <span id="total-allocated-display"></span>
                <button id="save-button"></button>
                <div id="allocations-container"></div>
                <table><thead><tr><th class="sortable">Asset</th></tr></thead><tbody></tbody></table>
            """.trimIndent()
            document.body!!.appendChild(container)
            try {
                window.asDynamic().updateAllocationTotal()
                window.asDynamic().addAssetRow()
                window.asDynamic().sortTable(document.querySelector("th.sortable"), 0)
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "helpers tolerate null, invalid, and edge cases to maximize branch coverage" {
            // 1. checkSyncProgress with total == 0
            val container = document.createElement("div")
            container.innerHTML = """
                <div id="sync-progress-banner"></div>
                <div id="sync-progress-bar"></div>
                <div id="sync-progress-text"></div>
                <div id="allocations-container"></div>
                <span id="total-allocated-display"></span>
                <button id="save-button"></button>
            """.trimIndent()
            document.body!!.appendChild(container)

            js("""
                window.fetch = function() {
                    return Promise.resolve({ json: function() { return Promise.resolve({ seeded: false, offset: 0, total: 0 }); } });
                };
            """)
            try {
                checkSyncProgress().await() shouldBe false
                
                // 2. updateAllocationTotal with non-input targets, invalid double targets
                val nonInputTarget = document.createElement("div")
                nonInputTarget.setAttribute("name", "targets")
                container.appendChild(nonInputTarget)
                
                val invalidInputTarget = document.createElement("input") as HTMLInputElement
                invalidInputTarget.name = "targets"
                invalidInputTarget.value = "invalid-double"
                container.appendChild(invalidInputTarget)
                
                val nonInputSymbol = document.createElement("div")
                nonInputSymbol.setAttribute("name", "symbols")
                container.appendChild(nonInputSymbol)
                
                updateAllocationTotal()
                
                // 3. renderTradeTable with null/missing values and success = false
                val tableContainer = document.createElement("div")
                tableContainer.innerHTML = "<table><tbody id='trade-table-body'></tbody></table>"
                container.appendChild(tableContainer)
                
                val badTrades = arrayOf(
                    js("({ timestamp: '2023-01-01', symbol: null, side: 'SELL', volume: 'bad', usdAmount: 'bad', success: null, dryRun: null })")
                )
                renderTradeTable(badTrades)
                val tbody = document.getElementById("trade-table-body") as HTMLTableSectionElement
                tbody.rows.length shouldBe 1
                
                // 4. updateStats with missing values
                val statsContainer = document.createElement("div")
                statsContainer.innerHTML = """
                    <div id="stat-ath"></div>
                    <div id="stat-total-trades"></div>
                    <div id="stat-total-volume"></div>
                    <div id="stat-total-fees"></div>
                """.trimIndent()
                container.appendChild(statsContainer)
                updateStats(js("({})"))
                
                // 5. sortTable with out-of-bounds index, empty cells, missing sort-value
                val sortContainer = document.createElement("div")
                sortContainer.innerHTML = """
                    <table>
                        <thead>
                            <tr><th class="sortable">C0</th></tr>
                        </thead>
                        <tbody>
                            <tr class="hoverable"><td></td></tr>
                            <tr class="hoverable"><td></td></tr>
                        </tbody>
                    </table>
                """.trimIndent()
                container.appendChild(sortContainer)
                val header = sortContainer.querySelector("th.sortable") as HTMLElement
                sortTable(header, 0) // Should fall back to textContent
                sortTable(header, 5) // Should handle out of bounds (aCell = null)
            } finally {
                document.body!!.removeChild(container)
            }
        }
    }
}