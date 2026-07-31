package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.*
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.*
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.js.json

private const val TEST_CHART = "test-chart"

class CoverageTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "createOrUpdate handles existing chart and visibility states" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <canvas id="$TEST_CHART"></canvas>
                """.trimIndent()
            document.body!!.appendChild(container)
            TestDomBuilders.setupMockChart(isDatasetVisible = { index -> index == 0 })
            try {
                registerHistoryGlobals()

                createOrUpdate(
                    TEST_CHART,
                    TestDomBuilders.chartConfig(),
                )

                createOrUpdate(
                    TEST_CHART,
                    TestDomBuilders.chartConfig(
                        TestDomBuilders.datasetConfig("A"),
                        TestDomBuilders.datasetConfig("B"),
                    ),
                )

                createOrUpdate(
                    TEST_CHART,
                    TestDomBuilders.chartConfig(
                        TestDomBuilders.datasetConfig(
                            "A",
                            hidden = false,
                        ),
                        TestDomBuilders.datasetConfig("B", hidden = true),
                    ),
                )

                ((window.asDynamic().chartCallCount as Int) >= 2).shouldBeTrue()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "createOrUpdate does nothing when canvas missing" {
            createOrUpdate("non-existent-canvas", json())
        }

        "calculateCumulativeNetCashFlow handles various trade scenarios" {
            val empty = calculateCumulativeNetCashFlow(emptyList())
            empty.size shouldBe 0

            val resultBuy =
                calculateCumulativeNetCashFlow(
                    listOf(
                        mockTradeRecord(
                            timestamp = "2023-01-01",
                            side = OrderSide.BUY.name,
                            usdAmount = "100.0",
                        ),
                    ),
                )
            resultBuy.size shouldBe 1
            val r0 = resultBuy[0]
            r0.y.toString().toDouble() shouldBe -100.0

            val resultSell =
                calculateCumulativeNetCashFlow(
                    listOf(
                        mockTradeRecord(
                            timestamp = "2023-01-01",
                            side = OrderSide.SELL.name,
                            usdAmount = "50.0",
                        ),
                    ),
                )
            resultSell.size shouldBe 1
            val s0 = resultSell[0]
            s0.y.toString().toDouble() shouldBe 50.0

            val mixed =
                listOf(
                    mockTradeRecord(
                        timestamp = "2023-01-01",
                        side = OrderSide.BUY.name,
                        usdAmount = "100.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-02",
                        success = false,
                        side = OrderSide.SELL.name,
                        usdAmount = "50.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-03",
                        dryRun = true,
                        side = OrderSide.BUY.name,
                        usdAmount = "200.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-04",
                        side = OrderSide.SELL.name,
                        usdAmount = "30.0",
                    ),
                )
            val resultMixed = calculateCumulativeNetCashFlow(mixed)
            resultMixed.size shouldBe 2
            val m0 = resultMixed[0]
            val m1 = resultMixed[1]
            (m0.y.toString().toDouble()) shouldBe -100.0
            (m1.y.toString().toDouble()) shouldBe (-100.0 + 30.0)
        }

        "renderTradeTable handles missing tbody and empty trades" {
            renderTradeTable(emptyList())

            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.tradeTableDom()
            document.body!!.appendChild(container)
            try {
                renderTradeTable(emptyList())
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.rows.length shouldBe 1
                tbody.innerHTML shouldContain "No trades found"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "updateStats handles missing elements gracefully" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.statsDom()
            document.body!!.appendChild(container)
            try {
                val stats = mockPortfolioStatsRecord()
                updateStats(stats)
                document.getElementById(HtmlIds.STAT_ATH)?.textContent shouldBe "$15,000.50"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "loadAll sets chartDefaults time unit based on range" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.historyDom()
            document.body!!.appendChild(container)
            val capturedUrls = mutableListOf<String>()
            window.asDynamic().capturedUrls = capturedUrls.toTypedArray()
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch =
                mockFetch(
                    mockHistoryFetchHandler(
                        syncProgress = json("seeded" to false, "offset" to "5", "total" to "10"),
                        stats =
                        mockPortfolioStatsRecord(
                            allTimeHigh = "100",
                            totalTradesExecuted = 1L,
                            totalVolumeTraded = "100",
                            totalFeesPaid = "1",
                        ),
                    ),
                )
            try {
                registerHistoryGlobals()

                // loadAll mutates a deep clone only — shared chartDefaults.time.unit must stay null.
                loadAll(TimeRange.TWENTY_FOUR_HOURS.key).await()
                (getClonedChartOptions().scales.x.time.unit as String) shouldBe "hour"
                (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true

                loadAll(TimeRange.ALL.key).await()
                (getClonedChartOptions().scales.x.time.unit == null) shouldBe true
                (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true

                loadAll(TimeRange.THIRTY_DAYS.key).await()
                (getClonedChartOptions().scales.x.time.unit as String) shouldBe "day"
                (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "checkSyncProgress handles banner missing, seeded true/false, and offset/total" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.syncProgressDom()
            document.body!!.appendChild(container)

            window.asDynamic().fetch = mockFetch { json("seeded" to true) }
            try {
                document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER)?.remove()
                checkSyncProgress().await() shouldBe true
            } finally {
                container.innerHTML = TestDomBuilders.syncProgressDom()
                document.body!!.appendChild(container)
            }

            window.asDynamic().fetch = mockFetch { json("seeded" to true) }
            try {
                checkSyncProgress().await() shouldBe true
                val banner = document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) as HTMLElement
                banner.style.display shouldBe "none"
            } finally {
            }

            window.asDynamic().fetch = mockFetch { json("seeded" to false, "offset" to 0, "total" to 100) }
            try {
                checkSyncProgress().await() shouldBe false
                val banner = document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) as HTMLElement
                banner.style.display shouldBe "block"
                val bar = document.getElementById(HtmlIds.SYNC_PROGRESS_BAR) as HTMLElement
                bar.style.width shouldBe "0%"
                val text = document.getElementById(HtmlIds.SYNC_PROGRESS_TEXT) as HTMLElement
                text.textContent shouldBe "0 / 100 (0%)"
            } finally {
            }

            window.asDynamic().fetch = mockFetch { json("seeded" to false, "offset" to 50, "total" to 100) }
            try {
                checkSyncProgress().await() shouldBe false
                val bar = document.getElementById(HtmlIds.SYNC_PROGRESS_BAR) as HTMLElement
                bar.style.width shouldBe "50%"
                val text = document.getElementById(HtmlIds.SYNC_PROGRESS_TEXT) as HTMLElement
                text.textContent shouldBe "50 / 100 (50%)"
            } finally {
            }

            window.asDynamic().fetch = { _: String -> Promise.reject(Throwable("Network error")) }
            try {
                checkSyncProgress().await() shouldBe false
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "updateAllocationTotal handles various input scenarios" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.settingsDom()
            document.body!!.appendChild(container)
            try {
                val input1 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                input1.name = FormFields.TARGETS
                input1.value = "40.0"
                val sym1 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                sym1.name = FormFields.SYMBOLS
                sym1.value = Asset.BTC
                val input2 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                input2.name = FormFields.TARGETS
                input2.value = "60.0"
                val sym2 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                sym2.name = FormFields.SYMBOLS
                sym2.value = Asset.USD
                container.appendChild(input1)
                container.appendChild(sym1)
                container.appendChild(input2)
                container.appendChild(sym2)

                updateAllocationTotal()
                val totalDisplay = document.getElementById(HtmlIds.TOTAL_ALLOCATED_DISPLAY) as HTMLSpanElement
                val saveButton = document.getElementById(HtmlIds.SAVE_BUTTON) as HTMLButtonElement
                totalDisplay.textContent shouldBe "Total: 100.00%"
                saveButton.disabled.shouldBeFalse()
                totalDisplay.classList.contains(CssClass.Form.AllocationTotalOk).shouldBeTrue()

                container.innerHTML = TestDomBuilders.settingsDom()
                document.body!!.appendChild(container)

                val input3 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                input3.name = FormFields.TARGETS
                input3.value = "30.0"
                val sym3 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                sym3.name = FormFields.SYMBOLS
                sym3.value = Asset.BTC
                val input4 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                input4.name = FormFields.TARGETS
                input4.value = "30.0"
                val sym4 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                sym4.name = FormFields.SYMBOLS
                sym4.value = Asset.USD
                container.appendChild(input3)
                container.appendChild(sym3)
                container.appendChild(input4)
                container.appendChild(sym4)

                updateAllocationTotal()
                val totalDisplay2 = document.getElementById(HtmlIds.TOTAL_ALLOCATED_DISPLAY) as HTMLSpanElement
                val saveButton2 = document.getElementById(HtmlIds.SAVE_BUTTON) as HTMLButtonElement
                totalDisplay2.textContent shouldBe "Total: 60.00%"
                saveButton2.disabled.shouldBeTrue()
                totalDisplay2.classList.contains(CssClass.Form.AllocationTotalBad).shouldBeTrue()

                container.innerHTML = TestDomBuilders.settingsDom()
                document.body!!.appendChild(container)
                val input5 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                input5.name = FormFields.TARGETS
                input5.value = "50.0"
                val sym5 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                sym5.name = FormFields.SYMBOLS
                sym5.value = Asset.BTC
                val input6 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                input6.name = FormFields.TARGETS
                input6.value = "50.0"
                val sym6 = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                sym6.name = FormFields.SYMBOLS
                sym6.value = "EETH"
                container.appendChild(input5)
                container.appendChild(sym5)
                container.appendChild(input6)
                container.appendChild(sym6)

                updateAllocationTotal()
                val totalDisplay3 = document.getElementById(HtmlIds.TOTAL_ALLOCATED_DISPLAY) as HTMLSpanElement
                val saveButton3 = document.getElementById(HtmlIds.SAVE_BUTTON) as HTMLButtonElement
                totalDisplay3.textContent shouldBe "Total: 100.00%"
                saveButton3.disabled.shouldBeTrue()
                totalDisplay3.classList.contains(CssClass.Form.AllocationTotalBad).shouldBeTrue()

                container.innerHTML = ""
                updateAllocationTotal()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "addAssetRow handles edge cases" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.assetEditDom(Asset.BTC)
            document.body!!.appendChild(container)
            try {
                val symbolInput = document.getElementById(HtmlIds.NEW_SYMBOL_INPUT) as HTMLInputElement
                symbolInput.value = ""
                addAssetRow()
                val allocContainer = document.getElementById(HtmlIds.ALLOCATIONS_CONTAINER) as HTMLElement
                allocContainer.childElementCount.shouldBe(0)

                symbolInput.value = Asset.BTC
                val existingRow = document.createElement(HtmlTags.DIV)
                existingRow.className = CssClass.Form.AllocationEditRow.toString()
                existingRow.innerHTML =
                    """
                    <input type="hidden" name="${FormFields.SYMBOLS}" value="${Asset.BTC}">
                    """.trimIndent()
                allocContainer.appendChild(existingRow)

                window.asDynamic().alertCalled = false
                window.asDynamic().alert = { _: String -> window.asDynamic().alertCalled = true }
                try {
                    addAssetRow()
                    (window.asDynamic().alertCalled as Boolean) shouldBe true
                    allocContainer.childElementCount.shouldBe(1)
                } finally {
                    window.asDynamic().alert = null
                }

                symbolInput.value = "NEW"
                allocContainer.remove()
                addAssetRow()
            } finally {
                if (container.parentNode != null) {
                    document.body!!.removeChild(container)
                }
            }
        }

        "updateAge handles missing elements and stale/fresh states" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.dataAgeDom()
            document.body!!.appendChild(container)
            try {
                updateAge()
                val ageVal = document.getElementsByClassName(CssClass.DataAge.Value.toString())[0] as HTMLSpanElement
                ageVal.textContent shouldBe ""

                val recentTime = Date.now() - 5000
                val timeEl = document.getElementsByClassName(CssClass.DataAge.Time.toString())[0] as HTMLSpanElement
                timeEl.setAttribute(HtmlAttrs.DATA_EPOCH, recentTime.toString())
                updateAge()
                ageVal.textContent shouldBe "5s ago"
                val badge = document.getElementsByClassName(CssClass.StatusCard.Badge.toString())[0] as HTMLElement
                badge.classList.contains(CssClass.Utility.Live).shouldBeTrue()
                badge.classList.contains(CssClass.Utility.Delayed).shouldBeFalse()

                // Past STALE_THRESHOLD_SECONDS (90): Utility.Live/Delayed CSS only — chip text is STREAM/STALE.
                val staleTime = Date.now() - 95000
                timeEl.setAttribute(HtmlAttrs.DATA_EPOCH, staleTime.toString())
                updateAge()
                ageVal.textContent shouldBe "95s ago"
                badge.classList.contains(CssClass.Utility.Delayed).shouldBeTrue()
                badge.classList.contains(CssClass.Utility.Live).shouldBeFalse()

                val amTime = Date(2023, 0, 1, 9, 30, 0).getTime()
                timeEl.setAttribute(HtmlAttrs.DATA_EPOCH, amTime.toString())
                updateAge()
                timeEl.textContent shouldBe "09:30:00 AM"

                val pmTime = Date(2023, 0, 1, 15, 30, 0).getTime()
                timeEl.setAttribute(HtmlAttrs.DATA_EPOCH, pmTime.toString())
                updateAge()
                timeEl.textContent shouldBe "03:30:00 PM"

                val noonTime = Date(2023, 0, 1, 12, 30, 0).getTime()
                timeEl.setAttribute(HtmlAttrs.DATA_EPOCH, noonTime.toString())
                updateAge()
                timeEl.textContent shouldBe "12:30:00 PM"

                val badgeContainer = document.createElement(HtmlTags.DIV)
                badgeContainer.innerHTML = TestDomBuilders.dataAgeDom("0")
                document.body!!.appendChild(badgeContainer)
                try {
                    updateAge()
                } finally {
                    document.body!!.removeChild(badgeContainer)
                }
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "reapplySort and sortTable handle edge cases" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.sortableTableDom()
            document.body!!.appendChild(container)
            try {
                val noHeadersContainer = document.createElement(HtmlTags.DIV)
                noHeadersContainer.innerHTML = TestDomBuilders.emptyTableDom()
                document.body!!.appendChild(noHeadersContainer)
                try {
                    reapplySort()
                } finally {
                    document.body!!.removeChild(noHeadersContainer)
                }

                val fakeHeader = document.createElement(HtmlTags.TH) as HTMLElement
                fakeHeader.className = CssClass.Table.Sortable.toString()
                sortTable(fakeHeader, 0)

                val sortableClass = CssClass.Table.Sortable.toString()
                val header0 =
                    document.getElementsByClassName(sortableClass)[0] as HTMLTableCellElement
                val header1 =
                    document.getElementsByClassName(sortableClass)[1] as HTMLTableCellElement

                sortTable(header0, 0)
                var rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                // "10" < "5" lexicographically
                (rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "A"

                sortTable(header0, 0, CssClass.Utility.Desc.toString())
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                // "5" > "10" lexicographically
                (rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "C"

                sortTable(header1, 1)
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                (rows.item(0) as HTMLTableRowElement).cells.item(1)?.textContent shouldBe "D"

                sortTable(header1, 1, CssClass.Utility.Desc.toString())
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                (rows.item(0) as HTMLTableRowElement).cells.item(1)?.textContent shouldBe "B"

                val row2 = document.createElement(HtmlTags.TR)
                row2.className = CssClass.Table.Hoverable.toString()
                val td2a = document.createElement(HtmlTags.TD)
                td2a.textContent = "Apple"
                val td2b = document.createElement(HtmlTags.TD)
                td2b.textContent = "Banana"
                row2.appendChild(td2a)
                row2.appendChild(td2b)
                container.querySelector(HtmlTags.TBODY)!!.appendChild(row2)

                sortTable(header0, 0)
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "registerDashboardGlobals exposes sortTable function" {
            registerDashboardGlobals()
            (window.asDynamic().sortTable != null) shouldBe true
        }

        "chart builders config callbacks cover tooltip and ticks formatting" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.chartsDom()
            document.body!!.appendChild(container)
            TestDomBuilders.setupMockChart()
            try {
                registerHistoryGlobals()
                val snapshots =
                    listOf(
                        mockSnapshotRecord(
                            timestamp = "2023-01-01",
                            totalValueUSD = "100",
                            assets =
                            mapOf(
                                Asset.USD to
                                    PortfolioSnapshot.AssetSnapshot(
                                        symbol = Asset.USD,
                                        balance = "100",
                                        price = "0",
                                        valueUSD = "100",
                                        targetPercent = "0",
                                        currentPercent = "100",
                                        deviationPercent = "5",
                                        deviationUSD = "0",
                                    ),
                            ),
                        ),
                        mockSnapshotRecord(
                            timestamp = "2023-01-02",
                            totalValueUSD = "160",
                            assets =
                            mapOf(
                                Asset.BTC to
                                    PortfolioSnapshot.AssetSnapshot(
                                        symbol = Asset.BTC,
                                        balance = "2",
                                        price = "0",
                                        valueUSD = "60",
                                        targetPercent = "0",
                                        currentPercent = "37.5",
                                        deviationPercent = "-25",
                                        deviationUSD = "0",
                                    ),
                                Asset.USD to
                                    PortfolioSnapshot.AssetSnapshot(
                                        symbol = Asset.USD,
                                        balance = "50",
                                        price = "0",
                                        valueUSD = "100",
                                        targetPercent = "0",
                                        currentPercent = "62.5",
                                        deviationPercent = "25",
                                        deviationUSD = "0",
                                    ),
                            ),
                        ),
                    )
                val trades =
                    listOf(
                        mockTradeRecord(
                            timestamp = "2023-01-01",
                            side = OrderSide.BUY.name,
                            usdAmount = "10",
                        ),
                    )

                buildPortfolioValueChart(snapshots)
                buildAssetHoldingsChart(snapshots)
                buildAllocationDriftChart(snapshots)
                buildCumulativeNetCashFlowChart(trades)

                val portConfig = window.asDynamic().chartConfigs[0]
                val label1 = portConfig.options.plugins.tooltip.callbacks.label
                val mockCtx1 =
                    jsObject {
                        dataset = json("label" to Asset.BTC)
                        parsed = json("y" to 12.3456)
                    }
                label1(mockCtx1).toString() shouldContain Asset.BTC
                val tick1 = portConfig.options.scales.y.ticks.callback
                tick1(12.34, 0, null).toString() shouldContain "$12.34"

                val holdingsConfig = window.asDynamic().chartConfigs[1]
                val label2 = holdingsConfig.options.plugins.tooltip.callbacks.label
                val mockCtx2 =
                    jsObject {
                        dataset = json("label" to Asset.BTC)
                        parsed = json("y" to 12.34)
                        dataIndex = 1
                    }
                label2(mockCtx2).toString() shouldContain "BTC: +12.34%"
                val mockCtx2USD =
                    jsObject {
                        dataset = json("label" to Asset.USD)
                        parsed = json("y" to -50.0)
                        dataIndex = 1
                    }
                label2(mockCtx2USD).toString() shouldContain "USD: -50.00%"
                val tick2 = holdingsConfig.options.scales.y.ticks.callback
                tick2(12.34, 0, null).toString() shouldBe "+12.34%"
                tick2(-5.6, 0, null).toString() shouldBe "-5.6%"

                val driftConfig = window.asDynamic().chartConfigs[2]
                val label3 = driftConfig.options.plugins.tooltip.callbacks.label
                val mockCtx3 =
                    jsObject {
                        dataset = json("label" to Asset.BTC)
                        parsed = json("y" to 12.34)
                    }
                label3(mockCtx3).toString() shouldBe "BTC: +12.34% vs target"
                val tick3 = driftConfig.options.scales.y.ticks.callback
                tick3(12.34, 0, null).toString() shouldBe "+12.34%"
                tick3(-5.6, 0, null).toString() shouldBe "-5.6%"
                (driftConfig.options.scales.y.beginAtZero as Boolean) shouldBe true
                val gridColor3 = driftConfig.options.scales.y.grid.color
                gridColor3(json("tick" to json("value" to 0))).toString() shouldBe ChartProps.COLOR_ZERO_LINE
                gridColor3(json("tick" to json("value" to 1))).toString() shouldBe ChartProps.COLOR_GRID_LINE

                val plConfig = window.asDynamic().chartConfigs[3]
                val tick4 = plConfig.options.scales.y.ticks.callback
                tick4(12.34, 0, null).toString() shouldContain "$12.34"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "initHistory sets up click listeners and checkbox listeners" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <button class="${CssClass.History.TimeRangeBtn}" ${HtmlAttrs.DATA_RANGE}="24h"></button>
                <button class="${CssClass.History.TimeRangeBtnActive}" ${HtmlAttrs.DATA_RANGE}="30d"></button>
                ${TestDomBuilders.syncProgressDom()}
                ${TestDomBuilders.chartsDom()}
                ${TestDomBuilders.tradeTableDom()}
                ${TestDomBuilders.statsDom()}
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

            var fetchCount = 0
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch =
                mockFetch { url ->
                    when {
                        url.contains("sync-progress") -> {
                            if (fetchCount++ == 0) {
                                json("seeded" to false, "offset" to 5, "total" to 10)
                            } else {
                                json("seeded" to true)
                            }
                        }
                        url.contains("snapshots") -> emptyArray<dynamic>()
                        url.contains("trades") -> emptyArray<dynamic>()
                        else -> json()
                    }
                }

            try {
                initHistory()

                awaitPromiseQueue()

                (intervalCb != null).shouldBeTrue()

                intervalCb?.invoke()

                awaitPromiseQueue()

                clearIntervalCalled.shouldBeTrue()

                val button24h =
                    document.querySelector(
                        "${HtmlTags.BUTTON}[${HtmlAttrs.DATA_RANGE}='${TimeRange.TWENTY_FOUR_HOURS.key}']",
                    ) as HTMLElement
                button24h.click()

                val checkbox = document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as HTMLInputElement
                checkbox.checked = false
                val event = document.createEvent("Event")
                event.initEvent(type = HtmlEvents.CHANGE, bubbles = true, cancelable = true)
                checkbox.dispatchEvent(event)

                awaitPromiseQueue()
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
                document.body!!.removeChild(container)
            }
        }

        "registerSettingsGlobals and registerDashboardGlobals wrappers can be called" {
            registerSettingsGlobals()
            registerDashboardGlobals()

            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                ${TestDomBuilders.assetEditDom("")}
                ${TestDomBuilders.settingsDom()}
                <table>
                  <thead>
                    <tr><th class="${CssClass.Table.Sortable}">${ViewText.HEADER_ASSET}</th></tr>
                  </thead>
                  <tbody></tbody>
                </table>
                """.trimIndent()
            document.body!!.appendChild(container)
            try {
                window.asDynamic().updateAllocationTotal()
                window.asDynamic().addAssetRow()
                window.asDynamic().sortTable(document.querySelector(CssClass.Query.SORTABLE_TH), 0)
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "helpers tolerate null, invalid, and edge cases to maximize branch coverage" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.settingsAndSyncDom()
            document.body!!.appendChild(container)

            window.asDynamic().fetch = mockFetch { json("seeded" to false, "offset" to 0, "total" to 0) }
            try {
                checkSyncProgress().await() shouldBe false

                val nonInputTarget = document.createElement(HtmlTags.DIV)
                nonInputTarget.setAttribute("name", FormFields.TARGETS)
                container.appendChild(nonInputTarget)

                val invalidInputTarget = document.createElement(HtmlTags.INPUT) as HTMLInputElement
                invalidInputTarget.name = FormFields.TARGETS
                invalidInputTarget.value = "invalid-double"
                container.appendChild(invalidInputTarget)

                val nonInputSymbol = document.createElement(HtmlTags.DIV)
                nonInputSymbol.setAttribute("name", FormFields.SYMBOLS)
                container.appendChild(nonInputSymbol)

                updateAllocationTotal()

                val tableContainer = document.createElement(HtmlTags.DIV)
                tableContainer.innerHTML = TestDomBuilders.emptyTradeTableDom()
                container.appendChild(tableContainer)

                renderTradeTable(
                    listOf(
                        mockTradeRecord(
                            symbol = "",
                            timestamp = "2023-01-01",
                            side = OrderSide.SELL.name,
                            volume = "bad",
                            usdAmount = "bad",
                        ),
                    ),
                )
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.rows.length shouldBe 1

                val statsContainer = document.createElement(HtmlTags.DIV)
                statsContainer.innerHTML = TestDomBuilders.statsDom()
                container.appendChild(statsContainer)
                updateStats(mockPortfolioStatsRecord(allTimeHigh = "0", totalTradesExecuted = 0L))

                val sortContainer = document.createElement(HtmlTags.DIV)
                sortContainer.innerHTML =
                    """
                    <table>
                        <thead>
                            <tr><th class="${CssClass.Table.Sortable}">C0</th></tr>
                        </thead>
                        <tbody>
                            <tr class="${CssClass.Table.Hoverable}"><td></td></tr>
                            <tr class="${CssClass.Table.Hoverable}"><td></td></tr>
                        </tbody>
                    </table>
                    """.trimIndent()
                container.appendChild(sortContainer)
                val header = sortContainer.querySelector(CssClass.Query.SORTABLE_TH) as HTMLElement
                sortTable(header, 0)
                sortTable(header, 5)
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "testDomExtensionsCompleteCoverage" {
            val div = document.createDiv()

            div.className = CssClass.Table.Sortable.value
            div.classList.contains(CssClass.Table.Sortable).shouldBeTrue()
            div.classList.remove(CssClass.Table.Sortable)
            div.classList.contains(CssClass.Table.Sortable).shouldBeFalse()

            div.classList.toggle(CssClass.Table.Sortable, true).shouldBeTrue()
            div.classList.toggle(CssClass.Table.Sortable, false).shouldBeFalse()
        }
    }
}
