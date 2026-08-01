package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeSourceKeys
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.w3c.dom.*

class HistoryTradeRenderingTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "formatPair handles valid and missing symbols" {
            formatPair(mockTradeRecord(symbol = Asset.BTC)) shouldBe "${Asset.BTC}/${Asset.USD}"
            formatPair(mockTradeRecord(symbol = "")) shouldBe ""
        }

        "renderTradeTable shows nine columns with price fee and slippage" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.tradeTableDom()
            document.body!!.appendChild(container)

            try {
                val trades =
                    listOf(
                        mockTradeRecord(
                            side = OrderSide.BUY.name,
                            price = "50000.0",
                            fee = "13.0",
                            slippagePercent = "0.5",
                            source = TradeSourceKeys.LOCAL_ESTIMATE,
                        ),
                        mockTradeRecord(
                            side = OrderSide.SELL.name,
                            success = false,
                            errorMessage = "Insufficient funds",
                            slippagePercent = null,
                        ),
                    )

                renderTradeTable(trades)
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.rows.length shouldBe 2
                val firstRow = tbody.rows.item(0) as HTMLTableRowElement
                firstRow.cells.length shouldBe PrecisionConstants.TRADE_TABLE_COLSPAN
                tbody.innerHTML shouldContain "badge-slippage-adverse"
                tbody.innerHTML shouldContain ViewText.EM_DASH
                tbody.innerHTML shouldContain ViewText.TRADE_FAILED_TITLE_PREFIX + "Insufficient funds"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "renderTradeTable maps lowercase buy side to buy badge" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.tradeTableDom()
            document.body!!.appendChild(container)

            try {
                renderTradeTable(
                    listOf(
                        mockTradeRecord(side = OrderSide.BUY.apiValue),
                    ),
                )
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.innerHTML shouldContain "badge-buy"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "renderTradeTable keeps sub-cent price and fee precision instead of rounding to zero" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.tradeTableDom()
            document.body!!.appendChild(container)

            try {
                val trades =
                    listOf(
                        mockTradeRecord(
                            side = OrderSide.BUY.name,
                            price = "0.0000753",
                            fee = "0.0033",
                        ),
                    )

                renderTradeTable(trades)
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.innerHTML shouldContain "$0.0000753"
                tbody.innerHTML shouldContain "$0.0033"
                tbody.innerHTML shouldContain CssClass.Table.StatusDot.toString()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "renderTradeTable filters dry runs and displays empty states" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.tradeTableDom()
            document.body!!.appendChild(container)

            try {
                val trades =
                    listOf(
                        mockTradeRecord(
                            timestamp = "2023-01-01",
                            symbol = Asset.BTC,
                            side = OrderSide.BUY.name,
                            volume = "0.1",
                            usdAmount = "2000.0",
                            success = true,
                            dryRun = false,
                        ),
                        mockTradeRecord(
                            timestamp = "2023-01-02",
                            symbol = Asset.ETH,
                            side = OrderSide.SELL.name,
                            volume = "1.0",
                            usdAmount = "1800.0",
                            success = true,
                            dryRun = true,
                        ),
                        mockTradeRecord(
                            timestamp = "2023-01-03",
                            symbol = Asset.LTC,
                            side = OrderSide.BUY.name,
                            volume = "5.0",
                            usdAmount = "350.0",
                            success = false,
                            dryRun = false,
                        ),
                    )

                renderTradeTable(trades)
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.rows.length shouldBe 3
                tbody.innerHTML shouldContain "${Asset.BTC}/${Asset.USD}"
                tbody.innerHTML shouldContain "${Asset.ETH}/${Asset.USD}"
                tbody.innerHTML shouldContain "${Asset.LTC}/${Asset.USD}"
                tbody.innerHTML shouldContain "DRY RUN"
                tbody.innerHTML shouldContain "FAILED"

                (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as HTMLInputElement).checked = false
                renderTradeTable(trades)
                tbody.rows.length shouldBe 2
                tbody.innerHTML shouldContain "${Asset.BTC}/${Asset.USD}"
                tbody.innerHTML shouldContain "${Asset.LTC}/${Asset.USD}"
                tbody.innerHTML shouldNotContain "${Asset.ETH}/${Asset.USD}"

                renderTradeTable(emptyList())
                tbody.rows.length shouldBe 1
                tbody.innerHTML shouldContain "No trades found"
            } finally {
                document.body!!.removeChild(container)
            }
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

        "renderTradeTable tolerates blank symbols and invalid amounts" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.emptyTradeTableDom()
            document.body!!.appendChild(container)
            try {
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
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "updateStats formats each displayed value" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.statsDom()
            document.body!!.appendChild(container)

            try {
                val stats = mockPortfolioStatsRecord()
                updateStats(stats)

                document.getElementById(HtmlIds.STAT_ATH)?.textContent shouldBe "$15,000.50"
                document.getElementById(HtmlIds.STAT_TOTAL_TRADES)?.textContent shouldBe "42"
                document.getElementById(HtmlIds.STAT_TOTAL_VOLUME)?.textContent shouldBe "$1,000,000.00"
                document.getElementById(HtmlIds.STAT_TOTAL_FEES)?.textContent shouldBe "$250.75"
                document.getElementById(HtmlIds.STAT_AVG_FEE_RATE)?.textContent shouldBe "0.26%"
                document.getElementById(HtmlIds.STAT_AVG_SLIPPAGE)?.textContent shouldBe "+0.15%"
            } finally {
                document.body!!.removeChild(container)
            }
        }
    }
}
