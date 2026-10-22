package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.Asset
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HistoryCsvExporter(private val historyService: HistoryService, private val historyStore: HistoryStore) {
    suspend fun exportDailyValues(query: HistoryDailyValueExportRange, writeChunk: suspend (String) -> Unit): Long {
        writeChunk(csvRow(DAILY_VALUE_HEADERS))
        var offset = 0
        var rowsWritten = 0L
        while (true) {
            val page = historyStore.queryDailyValues(
                accountIdentityDigest = query.accountIdentityDigest,
                sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                from = query.from,
                until = query.until,
                limit = EXPORT_PAGE_SIZE,
                offset = offset,
            )
            if (page.items.isEmpty()) return rowsWritten
            writeChunk(
                buildString {
                    page.items.forEach { value ->
                        append(
                            csvRow(
                                listOf(
                                    value.dayLabel.toString(),
                                    value.totalUsd?.toPlainString(),
                                    value.status.name,
                                    value.generationId,
                                    value.sourceRevision.toString(),
                                    value.assets.filter {
                                        it.status != HistoryDailyValueStatus.COMPLETE &&
                                            !it.rawAssetClass.equals("equity", ignoreCase = true)
                                    }
                                        .joinToString("; ") { asset ->
                                            "${Asset.normalizeLedgerAsset(
                                                asset.rawAssetKey,
                                            )}: ${asset.reason ?: asset.status.name}"
                                        },
                                ),
                            ),
                        )
                    }
                },
            )
            rowsWritten += page.items.size
            if (!page.hasMore) return rowsWritten
            offset += page.items.size
        }
    }

    suspend fun exportDailyHoldings(
        query: HistoryDailyHoldingsExportQuery,
        writeChunk: suspend (String) -> Unit,
    ): Long {
        writeChunk(csvRow(DAILY_HOLDING_HEADERS))
        val dayEnd = query.day.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant()
        val total = historyStore.queryDailyValues(
            accountIdentityDigest = query.accountIdentityDigest,
            sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
            from = dayEnd,
            until = dayEnd.plusSeconds(1),
            limit = 1,
        ).items.firstOrNull()
        if (total == null) {
            writeChunk(
                csvRow(
                    listOf(
                        query.day.toString(),
                        null,
                        "OUTSIDE_COVERAGE",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "No daily mark is available.",
                    ),
                ),
            )
            return 1
        }
        val page = historyStore.queryDailyAssetValues(
            accountIdentityDigest = query.accountIdentityDigest,
            sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
            from = dayEnd,
            until = dayEnd.plusSeconds(1),
            limit = EXPORT_PAGE_SIZE,
        )
        val includedAssets = page.items.filterNot { asset ->
            asset.rawAssetClass.equals("equity", ignoreCase = true)
        }
        if (includedAssets.isEmpty()) {
            writeChunk(
                csvRow(
                    listOf(
                        query.day.toString(),
                        total.totalUsd?.toPlainString(),
                        total.status.name,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "No held assets.",
                    ),
                ),
            )
            return 1
        }
        writeChunk(
            buildString {
                includedAssets.forEach { asset ->
                    append(
                        csvRow(
                            listOf(
                                query.day.toString(),
                                total.totalUsd?.toPlainString(),
                                total.status.name,
                                Asset.normalizeLedgerAsset(asset.rawAssetKey),
                                asset.quantity?.toPlainString(),
                                asset.priceUsd?.toPlainString(),
                                asset.valueUsd?.toPlainString(),
                                asset.status.name,
                                asset.reason,
                            ),
                        ),
                    )
                }
            },
        )
        return includedAssets.size.toLong()
    }

    suspend fun exportTrades(query: HistoryEventQuery, zone: ZoneId, writeChunk: suspend (String) -> Unit): Long =
        stream(
            headers = TRADE_HEADERS,
            fetchPage = { limit, cursor ->
                historyService.queryTrades(query.copy(limit = limit, offset = 0, sortCursor = cursor))
            },
            cursorOf = { HistorySortCursor(it.time, it.tradeId) },
            toCells = { trade ->
                listOf(
                    trade.tradeId.asCsvText(),
                    trade.orderTxid.asCsvText(),
                    trade.postTxid.asCsvText(),
                    trade.pair.asCsvText(),
                    trade.type.asCsvText(),
                    trade.orderType.asCsvText(),
                    trade.price.asCsvNumber(),
                    trade.volume.asCsvNumber(),
                    trade.baseAsset.asCsvText(),
                    trade.baseAsset.asCsvText(),
                    trade.cost.asCsvNumber(),
                    trade.quoteCurrency.asCsvText(),
                    trade.fee.asCsvNumber(),
                    (trade.feeCurrency ?: trade.quoteCurrency).asCsvText(),
                    trade.margin.asCsvNumber(),
                    trade.misc.asCsvText(),
                    trade.tradeSequenceId.asCsvText(),
                    trade.maker?.toString(),
                    jsonArray(trade.ledgerIds).asCsvText(),
                    trade.time.utcTimestamp(),
                    trade.time.localTimestamp(zone),
                    zone.id.asCsvText(),
                    HistorySourceMode.ACCOUNT.name,
                    UNKNOWN_LINKAGE,
                    trade.rawTime.asCsvNumber(),
                )
            },
            writeChunk = writeChunk,
        )

    suspend fun exportLedgers(query: HistoryEventQuery, zone: ZoneId, writeChunk: suspend (String) -> Unit): Long =
        stream(
            headers = LEDGER_HEADERS,
            fetchPage = { limit, cursor ->
                historyService.queryLedgers(query.copy(limit = limit, offset = 0, sortCursor = cursor))
            },
            cursorOf = { HistorySortCursor(it.time, it.ledgerId) },
            toCells = { ledger ->
                listOf(
                    ledger.ledgerId.asCsvText(),
                    ledger.refid.asCsvText(),
                    ledger.type.asCsvText(),
                    ledger.subtype.asCsvText(),
                    ledger.assetClass.asCsvText(),
                    ledger.asset.asCsvText(),
                    ledger.amount.asCsvNumber(),
                    ledger.asset.asCsvText(),
                    ledger.fee.asCsvNumber(),
                    ledger.feeCurrency.asCsvText(),
                    ledger.balance.asCsvNumber(),
                    ledger.asset.asCsvText(),
                    ledger.time.utcTimestamp(),
                    ledger.rawTime.asCsvNumber(),
                    ledger.time.localTimestamp(zone),
                    zone.id.asCsvText(),
                    HistorySourceMode.ACCOUNT.name,
                    UNKNOWN_LINKAGE,
                )
            },
            writeChunk = writeChunk,
        )

    suspend fun exportActivity(query: HistoryActivityQuery, zone: ZoneId, writeChunk: suspend (String) -> Unit): Long =
        stream(
            headers = ACTIVITY_HEADERS,
            fetchPage = { limit, cursor ->
                historyStore.queryActivityPage(query.copy(limit = limit, offset = 0, sortCursor = cursor))
            },
            cursorOf = { HistorySortCursor(it.occurredAt, it.cycleId) },
            toCells = { activity ->
                listOf(
                    activity.cycleId.asCsvText(),
                    activity.sourceScope.mode.name,
                    activity.outcome.csvLabel(),
                    activity.dryRun.toString(),
                    jsonArray(activity.actions).asCsvText(),
                    activity.occurredAt.utcTimestamp(),
                    activity.occurredAt.localTimestamp(zone),
                    zone.id.asCsvText(),
                    "Recorded cycle activity",
                )
            },
            writeChunk = writeChunk,
        )

    private suspend fun <T> stream(
        headers: List<String>,
        fetchPage: suspend (limit: Int, cursor: HistorySortCursor?) -> HistoryPage<T>,
        cursorOf: (T) -> HistorySortCursor,
        toCells: (T) -> List<String?>,
        writeChunk: suspend (String) -> Unit,
    ): Long {
        writeChunk(csvRow(headers))
        var cursor: HistorySortCursor? = null
        var rowsWritten = 0L
        while (true) {
            val page = fetchPage(EXPORT_PAGE_SIZE, cursor)
            check(page.items.size <= EXPORT_PAGE_SIZE) { "History CSV query exceeded the export page size." }
            check(page.items.isNotEmpty() || !page.hasMore) {
                "History CSV query returned an empty page with more rows."
            }
            if (page.items.isEmpty()) return rowsWritten

            val chunk = buildString {
                page.items.forEach { append(csvRow(toCells(it))) }
            }
            writeChunk(chunk)
            rowsWritten += page.items.size
            if (!page.hasMore) return rowsWritten
            val nextCursor = cursorOf(page.items.last())
            check(nextCursor != cursor) { "History CSV query did not advance its sort cursor." }
            cursor = nextCursor
        }
    }

    private fun HistoryCycleOutcome?.csvLabel(): String = when (this) {
        null, HistoryCycleOutcome.RECORDED -> "Recorded activity"
        HistoryCycleOutcome.NO_TRADE -> "No trade"
        HistoryCycleOutcome.DRY_RUN_PLAN -> "Dry-run plan"
        HistoryCycleOutcome.ORDERS_ATTEMPTED -> "Orders attempted"
        HistoryCycleOutcome.PROBLEM -> "Problem"
    }

    private fun csvRow(cells: List<String?>): String = buildString {
        cells.forEachIndexed { index, cell ->
            if (index > 0) append(',')
            append('"')
            append(cell.orEmpty().replace("\"", "\"\""))
            append('"')
        }
        append("\r\n")
    }

    private fun jsonArray(values: List<String>): String =
        values.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"${it.jsonEscaped()}\"" }

    private fun String.jsonEscaped(): String = buildString {
        this@jsonEscaped.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }

    private fun String?.asCsvText(): String? = this?.let { if (it.startsWithFormulaMarker()) "'$it" else it }

    private fun String?.asCsvNumber(): String? = this?.let {
        if (it.toBigDecimalOrNull() ==
            null
        ) {
            it.asCsvText()
        } else {
            it
        }
    }

    private fun String.startsWithFormulaMarker(): Boolean =
        firstOrNull { !it.isWhitespace() && !it.isISOControl() }?.let { it in FORMULA_MARKERS } == true

    private fun Instant?.utcTimestamp(): String? = this?.toString()

    private fun Instant?.localTimestamp(zone: ZoneId): String? =
        this?.atZone(zone)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private companion object {
        const val EXPORT_PAGE_SIZE = 500
        const val UNKNOWN_LINKAGE = "Unknown"

        val FORMULA_MARKERS = setOf('=', '+', '-', '@')

        val TRADE_HEADERS = listOf(
            "trade_id",
            "order_txid",
            "post_txid",
            "pair",
            "side",
            "order_type",
            "price",
            "volume",
            "base_asset",
            "volume_currency",
            "cost",
            "quote_currency",
            "fee",
            "fee_currency",
            "margin",
            "misc",
            "trade_sequence_id",
            "maker",
            "ledger_ids",
            "utc_timestamp",
            "local_timestamp",
            "display_timezone",
            "mode",
            "bot_linkage",
            "raw_timestamp",
        )

        val LEDGER_HEADERS = listOf(
            "ledger_id",
            "refid",
            "type",
            "subtype",
            "asset_class",
            "asset",
            "amount",
            "amount_currency",
            "fee",
            "fee_currency",
            "balance",
            "balance_currency",
            "utc_timestamp",
            "raw_timestamp",
            "local_timestamp",
            "display_timezone",
            "mode",
            "bot_linkage",
        )

        val ACTIVITY_HEADERS = listOf(
            "cycle_id",
            "mode",
            "outcome",
            "dry_run",
            "actions",
            "utc_timestamp",
            "local_timestamp",
            "display_timezone",
            "source_linkage",
        )

        val DAILY_VALUE_HEADERS = listOf(
            "day_utc",
            "account_value_usd",
            "availability",
            "generation_id",
            "source_revision",
            "missing_asset_reasons",
        )

        val DAILY_HOLDING_HEADERS = listOf(
            "day_utc",
            "account_value_usd",
            "account_availability",
            "asset",
            "quantity",
            "historical_price_usd",
            "value_usd",
            "availability",
            "reason",
        )
    }
}
