package com.gemini.krakenbot.service.history

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId

class HistoryCsvExporterTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val historyService = mockk<HistoryService>()
    private val historyStore = mockk<HistoryStore>()
    private val exporter = HistoryCsvExporter(historyService, historyStore)

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val simulationScope = HistorySourceScope(
        mode = HistorySourceMode.SIMULATION,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = listOf("BTC", "USD"),
        simulationSessionId = "sim-test-session",
    )

    init {
        "quotes every field and escapes RFC-4180 commas quotes and embedded newlines" {
            val record = trade(pair = "XBT,USD", misc = "memo, \"quoted\"\r\nsecond line")
            coEvery { historyService.queryTrades(any()) } returns pageOf(listOf(record))
            val chunks = mutableListOf<String>()

            exporter.exportTrades(HistoryEventQuery(accountIdentityDigest = accountDigest), ZoneId.of("UTC")) {
                chunks += it
            } shouldBe 1L

            val csv = chunks.joinToString("")
            csv.startsWith("\"trade_id\",\"order_txid\",\"post_txid\"") shouldBe true
            csv.contains("\"XBT,USD\"") shouldBe true
            csv.contains("\"memo, \"\"quoted\"\"\r\nsecond line\"") shouldBe true
            val rows = parseCsv(csv)
            rows.size shouldBe 2
            rows[1][0] shouldBe "trade-1"
            rows[1][15] shouldBe "memo, \"quoted\"\r\nsecond line"
            csv.contains("do-not-export") shouldBe false
            coVerify(exactly = 1) { historyService.queryTrades(any()) }
            confirmVerified(historyService, historyStore)
        }

        "neutralizes formula-leading text but preserves valid raw negative numerics and nulls" {
            coEvery { historyService.queryTrades(any()) } returns pageOf(
                listOf(
                    trade(
                        tradeId = "=HYPERLINK(1)",
                        orderTxid = "+ORDER",
                        postTxid = "-POST",
                        pair = "@PAIR",
                        type = "=SIDE",
                        orderType = "+TYPE",
                        price = "-0.00000001",
                        volume = "-0.25",
                        baseAsset = "=ASSET",
                        cost = null,
                        fee = "=1+1",
                        feeCurrency = "-FEE",
                        margin = "-invalid",
                        misc = "@TEXT",
                        tradeSequenceId = "\t=HYPERLINK(1)",
                    ),
                ),
            )
            val chunks = mutableListOf<String>()

            exporter.exportTrades(HistoryEventQuery(), ZoneId.of("UTC")) { chunks += it } shouldBe 1L

            val row = recordMaps(chunks.joinToString("")).single()
            row["trade_id"] shouldBe "'=HYPERLINK(1)"
            row["order_txid"] shouldBe "'+ORDER"
            row["post_txid"] shouldBe "'-POST"
            row["pair"] shouldBe "'@PAIR"
            row["side"] shouldBe "'=SIDE"
            row["order_type"] shouldBe "'+TYPE"
            row["base_asset"] shouldBe "'=ASSET"
            row["fee_currency"] shouldBe "'-FEE"
            row["misc"] shouldBe "'@TEXT"
            row["trade_sequence_id"] shouldBe "'\t=HYPERLINK(1)"
            row["price"] shouldBe "-0.00000001"
            row["volume"] shouldBe "-0.25"
            row["cost"] shouldBe ""
            row["fee"] shouldBe "'=1+1"
            row["margin"] shouldBe "'-invalid"
            row["bot_linkage"] shouldBe "Unknown"
            coVerify(exactly = 1) { historyService.queryTrades(any()) }
            confirmVerified(historyService, historyStore)
        }

        "exports Kraken trade fee currency using its quote currency" {
            coEvery { historyService.queryTrades(any()) } returns pageOf(
                listOf(trade(quoteCurrency = "USDT", feeCurrency = null)),
            )
            val chunks = mutableListOf<String>()

            exporter.exportTrades(HistoryEventQuery(), ZoneId.of("UTC")) { chunks += it } shouldBe 1L

            recordMaps(chunks.joinToString("")).single()["fee_currency"] shouldBe "USDT"
            coVerify(exactly = 1) { historyService.queryTrades(any()) }
            confirmVerified(historyService, historyStore)
        }

        "writes absent values as empty cells and keeps empty arrays distinct" {
            coEvery { historyService.queryTrades(any()) } returns pageOf(
                listOf(
                    trade(
                        orderTxid = null,
                        postTxid = null,
                        pair = null,
                        type = null,
                        orderType = null,
                        price = null,
                        volume = null,
                        baseAsset = null,
                        quoteCurrency = null,
                        fee = null,
                        feeCurrency = null,
                        cost = null,
                        margin = null,
                        misc = "",
                        tradeSequenceId = null,
                        maker = null,
                        ledgerIds = emptyList(),
                        time = null,
                        rawTime = null,
                    ),
                ),
            )
            val chunks = mutableListOf<String>()

            exporter.exportTrades(HistoryEventQuery(), ZoneId.of("UTC")) { chunks += it } shouldBe 1L

            val row = recordMaps(chunks.joinToString("")).single()
            row["order_txid"] shouldBe ""
            row["price"] shouldBe ""
            row["cost"] shouldBe ""
            row["misc"] shouldBe ""
            row["maker"] shouldBe ""
            row["ledger_ids"] shouldBe "[]"
            row["utc_timestamp"] shouldBe ""
            row["local_timestamp"] shouldBe ""
            row["raw_timestamp"] shouldBe ""
            coVerify(exactly = 1) { historyService.queryTrades(any()) }
            confirmVerified(historyService, historyStore)
        }

        "streams more than one page without changing stable order or losing record ids" {
            val records = (0 until 503).map { index ->
                trade(
                    tradeId = "trade-${index.toString().padStart(3, '0')}",
                    time = Instant.parse("2025-01-01T00:00:00Z").minusSeconds(index.toLong()),
                )
            }
            val start = Instant.parse("2025-01-01T00:00:00Z")
            val until = Instant.parse("2025-02-01T00:00:00Z")
            val query = HistoryEventQuery(
                from = start,
                until = until,
                accountIdentityDigest = accountDigest,
                assetKey = "XXBT",
                pair = "XBTUSD",
                type = "buy",
                limit = 19,
                offset = 38,
            )
            val requestedQueries = mutableListOf<HistoryEventQuery>()
            coEvery { historyService.queryTrades(any()) } coAnswers {
                val pageQuery = firstArg<HistoryEventQuery>()
                requestedQueries += pageQuery
                val startIndex = pageQuery.sortCursor?.let { cursor ->
                    records.indexOfFirst { it.tradeId == cursor.id } + 1
                } ?: 0
                val items = records.drop(startIndex).take(pageQuery.limit)
                HistoryPage(
                    items = items,
                    total = (records.size - startIndex).toLong(),
                    limit = pageQuery.limit,
                    offset = pageQuery.offset,
                    hasMore = startIndex + items.size < records.size,
                )
            }
            val chunks = mutableListOf<String>()

            val rowsWritten = exporter.exportTrades(query, ZoneId.of("UTC")) { chunks += it }

            rowsWritten shouldBe records.size.toLong()
            chunks.size shouldBe 3
            chunks.drop(1).all { countCsvRecords(it) <= 500 } shouldBe true
            requestedQueries.map { it.limit } shouldBe listOf(500, 500)
            requestedQueries.map { it.offset } shouldBe listOf(0, 0)
            requestedQueries.map { it.sortCursor } shouldBe listOf(
                null,
                HistorySortCursor(records[499].time, records[499].tradeId),
            )
            requestedQueries.forEach { pageQuery ->
                pageQuery.from shouldBe start
                pageQuery.until shouldBe until
                pageQuery.accountIdentityDigest shouldBe accountDigest
                pageQuery.assetKey shouldBe "XXBT"
                pageQuery.pair shouldBe "XBTUSD"
                pageQuery.type shouldBe "buy"
            }
            val exportedIds = recordMaps(chunks.joinToString("")).map { it.getValue("trade_id") }
            exportedIds shouldBe records.map { it.tradeId }
            exportedIds.toSet().size shouldBe records.size
            coVerify(exactly = 2) { historyService.queryTrades(any()) }
            confirmVerified(historyService, historyStore)
        }

        "export row count and identifiers reconcile exactly to filtered pages" {
            val records = listOf(trade(tradeId = "trade-a"), trade(tradeId = "trade-b"))
            val query = HistoryEventQuery(
                from = Instant.parse("2024-01-01T00:00:00Z"),
                until = Instant.parse("2024-02-01T00:00:00Z"),
                accountIdentityDigest = accountDigest,
                assetKey = "XXBT",
                type = "sell",
            )
            coEvery { historyService.queryTrades(any()) } returns pageOf(records)
            val chunks = mutableListOf<String>()

            exporter.exportTrades(query, ZoneId.of("UTC")) { chunks += it } shouldBe 2L

            val exported = recordMaps(chunks.joinToString("")).map { it.getValue("trade_id") }
            exported shouldBe listOf("trade-a", "trade-b")
            coVerify(exactly = 1) {
                historyService.queryTrades(query.copy(limit = 500, offset = 0))
            }
            confirmVerified(historyService, historyStore)
        }

        "empty filtered results emit only the CSV header" {
            coEvery { historyService.queryTrades(any()) } returns pageOf(emptyList())
            val chunks = mutableListOf<String>()

            exporter.exportTrades(HistoryEventQuery(pair = "NO-MATCH"), ZoneId.of("UTC")) { chunks += it } shouldBe 0L

            chunks.size shouldBe 1
            parseCsv(chunks.single()).size shouldBe 1
            coVerify(exactly = 1) { historyService.queryTrades(any()) }
            confirmVerified(historyService, historyStore)
        }

        "rejects an oversized page before writing more than the bounded chunk size" {
            val records = (0..500).map { index -> trade(tradeId = "trade-$index") }
            coEvery { historyService.queryTrades(any()) } returns HistoryPage(records, 501, 500, 0, true)
            val chunks = mutableListOf<String>()

            shouldThrow<IllegalStateException> {
                exporter.exportTrades(HistoryEventQuery(), ZoneId.of("UTC")) { chunks += it }
            }

            chunks.size shouldBe 1
            coVerify(exactly = 1) { historyService.queryTrades(any()) }
            confirmVerified(historyService, historyStore)
        }

        "fails rather than looping when an empty page claims continuation" {
            coEvery { historyService.queryTrades(any()) } returns HistoryPage(emptyList(), 1, 500, 0, true)
            val chunks = mutableListOf<String>()

            shouldThrow<IllegalStateException> {
                exporter.exportTrades(HistoryEventQuery(), ZoneId.of("UTC")) { chunks += it }
            }

            chunks.size shouldBe 1
            coVerify(exactly = 1) { historyService.queryTrades(any()) }
            confirmVerified(historyService, historyStore)
        }

        "preserves ledger asset units and renders the same instant in UTC and browser time" {
            val time = Instant.parse("2024-03-10T09:30:00Z")
            coEvery { historyService.queryLedgers(any()) } returns pageOfLedgers(
                listOf(
                    ledger(
                        time = time,
                        rawTime = "1710063000.2500",
                        assetClass = "currency",
                        asset = "XXBT",
                        amount = "-0.12345678",
                        fee = "0.00010000",
                        balance = "1.23456789",
                    ),
                ),
            )
            val query = HistoryEventQuery(
                from = Instant.parse("2024-03-01T00:00:00Z"),
                until = Instant.parse("2024-04-01T00:00:00Z"),
                accountIdentityDigest = accountDigest,
                assetKey = "XXBT",
                type = "trade",
            )
            val chunks = mutableListOf<String>()

            exporter.exportLedgers(query, ZoneId.of("America/Los_Angeles")) { chunks += it } shouldBe 1L

            val row = recordMaps(chunks.joinToString("")).single()
            row["ledger_id"] shouldBe "ledger-1"
            row["asset_class"] shouldBe "currency"
            row["asset"] shouldBe "XXBT"
            row["amount"] shouldBe "-0.12345678"
            row["amount_currency"] shouldBe "XXBT"
            row["fee"] shouldBe "0.00010000"
            row["fee_currency"] shouldBe "XXBT"
            row["balance"] shouldBe "1.23456789"
            row["balance_currency"] shouldBe "XXBT"
            row["raw_timestamp"] shouldBe "1710063000.2500"
            row["utc_timestamp"] shouldBe "2024-03-10T09:30:00Z"
            row["local_timestamp"] shouldBe "2024-03-10T01:30:00-08:00"
            row["display_timezone"] shouldBe "America/Los_Angeles"
            row["mode"] shouldBe "ACCOUNT"
            row["bot_linkage"] shouldBe "Unknown"
            coVerify(exactly = 1) { historyService.queryLedgers(query.copy(limit = 500, offset = 0)) }
            confirmVerified(historyService, historyStore)
        }

        "keeps recorded cycle action order and labels legacy outcomes without inference" {
            val from = Instant.parse("2025-04-01T00:00:00Z")
            val until = Instant.parse("2025-05-01T00:00:00Z")
            val query = HistoryActivityQuery(simulationScope, from, until, limit = 11, offset = 22)
            val activities = listOf(
                activity(
                    "cycle-6",
                    actions = listOf(
                        "sell",
                        "settle",
                        "buy",
                        "slash\\",
                        "quote\"",
                        "backspace\b",
                        "formfeed\u000C",
                        "line\n",
                        "return\r",
                        "tab\t",
                        "control\u0001",
                    ),
                    outcome = HistoryCycleOutcome.PROBLEM,
                ),
                activity("cycle-5", actions = emptyList(), outcome = HistoryCycleOutcome.RECORDED),
                activity("cycle-4", actions = emptyList(), outcome = HistoryCycleOutcome.DRY_RUN_PLAN),
                activity("cycle-3", actions = emptyList(), outcome = HistoryCycleOutcome.NO_TRADE),
                activity(
                    "cycle-2",
                    actions = listOf("sell", "settle", "buy"),
                    outcome = HistoryCycleOutcome.ORDERS_ATTEMPTED,
                ),
                activity("cycle-1", actions = listOf("no trade check"), outcome = null),
            )
            coEvery { historyStore.queryActivityPage(any()) } coAnswers {
                val pageQuery = firstArg<HistoryActivityQuery>()
                HistoryPage(
                    activities.drop(pageQuery.offset).take(pageQuery.limit),
                    activities.size.toLong(),
                    pageQuery.limit,
                    pageQuery.offset,
                    false,
                )
            }
            val chunks = mutableListOf<String>()

            exporter.exportActivity(query, ZoneId.of("Europe/Paris")) { chunks += it } shouldBe activities.size.toLong()

            val rows = recordMaps(chunks.joinToString(""))
            rows.map { it.getValue("cycle_id") } shouldBe activities.map { it.cycleId }
            rows[0]["mode"] shouldBe "SIMULATION"
            rows[0]["outcome"] shouldBe "Problem"
            rows[0]["actions"] shouldBe
                """["sell","settle","buy","slash\\","quote\"","backspace\b","formfeed\f","line\n","return\r","tab\t","control\u0001"]"""
            rows[0]["source_linkage"] shouldBe "Recorded cycle activity"
            rows.map { it.getValue("outcome") } shouldBe listOf(
                "Problem",
                "Recorded activity",
                "Dry-run plan",
                "No trade",
                "Orders attempted",
                "Recorded activity",
            )
            rows.last()["actions"] shouldBe "[\"no trade check\"]"
            coVerify(exactly = 1) {
                historyStore.queryActivityPage(query.copy(limit = 500, offset = 0))
            }
            confirmVerified(historyService, historyStore)
        }
    }

    private fun pageOf(trades: List<HistoryTrade>): HistoryPage<HistoryTrade> =
        HistoryPage(trades, trades.size.toLong(), 500, 0, false)

    private fun pageOfLedgers(ledgers: List<HistoryLedger>): HistoryPage<HistoryLedger> =
        HistoryPage(ledgers, ledgers.size.toLong(), 500, 0, false)

    private fun trade(
        tradeId: String = "trade-1",
        orderTxid: String? = "order-1",
        postTxid: String? = "post-1",
        pair: String? = "XBTUSD",
        type: String? = "buy",
        orderType: String? = "limit",
        price: String? = "60000.00",
        volume: String? = "0.25",
        baseAsset: String? = "XXBT",
        quoteCurrency: String? = "ZUSD",
        fee: String? = "5.00",
        feeCurrency: String? = "ZUSD",
        cost: String? = "15000.00",
        margin: String? = null,
        misc: String? = null,
        tradeSequenceId: String? = "sequence-1",
        maker: Boolean? = true,
        ledgerIds: List<String> = listOf("ledger-1"),
        time: Instant? = Instant.parse("2024-01-15T12:30:00Z"),
        rawTime: String? = "1705321800.0000",
    ): HistoryTrade = HistoryTrade(
        accountIdentityDigest = accountDigest,
        tradeId = tradeId,
        orderTxid = orderTxid,
        postTxid = postTxid,
        pair = pair,
        baseAsset = baseAsset,
        quoteCurrency = quoteCurrency,
        feeCurrency = feeCurrency,
        time = time,
        rawTime = rawTime,
        type = type,
        orderType = orderType,
        price = price,
        cost = cost,
        fee = fee,
        volume = volume,
        margin = margin,
        misc = misc,
        tradeSequenceId = tradeSequenceId,
        maker = maker,
        ledgerIds = ledgerIds,
        rawRecordJson = "{\"private\":\"do-not-export\"}",
        hasValidTime = true,
        hasValidPrice = true,
        hasValidCost = true,
        hasValidFee = true,
        hasValidVolume = true,
        isValidRecord = true,
    )

    private fun ledger(
        time: Instant? = Instant.parse("2024-01-15T12:30:00Z"),
        rawTime: String? = "1705321800.0000",
        assetClass: String? = "currency",
        asset: String? = "XXBT",
        amount: String? = "-0.12345678",
        fee: String? = "0.00010000",
        balance: String? = "1.23456789",
    ): HistoryLedger = HistoryLedger(
        accountIdentityDigest = accountDigest,
        ledgerId = "ledger-1",
        refid = "reference-1",
        time = time,
        rawTime = rawTime,
        type = "trade",
        subtype = "spot trade",
        assetClass = assetClass,
        asset = asset,
        amount = amount,
        fee = fee,
        balance = balance,
        rawRecordJson = "{\"private\":\"do-not-export\"}",
        hasValidTime = true,
        hasValidAmount = true,
        hasValidFee = true,
        hasValidBalance = true,
        isValidRecord = true,
    )

    private fun activity(cycleId: String, actions: List<String>, outcome: HistoryCycleOutcome?): HistoryCycleActivity =
        HistoryCycleActivity(
            cycleId = cycleId,
            sourceScope = simulationScope,
            occurredAt = Instant.parse("2025-04-15T10:00:00Z"),
            dryRun = true,
            actions = actions,
            outcome = outcome,
        )

    private fun recordMaps(csv: String): List<Map<String, String>> {
        val rows = parseCsv(csv)
        val headers = rows.first()
        return rows.drop(1).map { headers.zip(it).toMap() }
    }

    private fun countCsvRecords(csv: String): Int = parseCsv(csv).drop(1).size

    private fun parseCsv(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < csv.length) {
            val char = csv[index]
            if (quoted) {
                if (char == '"') {
                    if (csv.getOrNull(index + 1) == '"') {
                        cell.append('"')
                        index++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(char)
                }
            } else {
                when (char) {
                    '"' -> {
                        check(cell.isEmpty())
                        quoted = true
                    }

                    ',' -> {
                        row += cell.toString()
                        cell.clear()
                    }

                    '\r' -> {
                        check(csv.getOrNull(index + 1) == '\n')
                        row += cell.toString()
                        rows += row.toList()
                        row = mutableListOf()
                        cell.clear()
                        index++
                    }

                    else -> error("Unexpected character outside a quoted CSV cell.")
                }
            }
            index++
        }
        check(!quoted)
        if (row.isNotEmpty() || cell.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }
}
