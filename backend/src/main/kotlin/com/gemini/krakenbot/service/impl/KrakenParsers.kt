package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.JsonNode
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.domain.safeParseBigDecimal
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.util.PrecisionConstants
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant

object KrakenParsers {
    private val log = LoggerFactory.getLogger(KrakenParsers::class.java)

    fun parseBalances(result: JsonNode): RawBalances = result
        .properties()
        .mapNotNull { (key, value) ->
            val amount = safeParseBigDecimal(value.asText())
            if (amount > BigDecimal.ZERO) key to amount else null
        }.toMap()

    fun parseSpendableBalances(result: JsonNode): RawBalances = result
        .properties()
        .mapNotNull { (key, value) ->
            if (!value.isObject) return@mapNotNull null
            val balance = safeParseBigDecimal(value.path(KrakenApiConstants.FIELD_BALANCE).asText())
            val credit = safeParseBigDecimal(value.path(KrakenApiConstants.FIELD_CREDIT).asText())
            val creditUsed = safeParseBigDecimal(value.path(KrakenApiConstants.FIELD_CREDIT_USED).asText())
            val holdTrade = safeParseBigDecimal(value.path(KrakenApiConstants.FIELD_HOLD_TRADE).asText())
            val available = balance.add(credit).subtract(creditUsed).subtract(holdTrade)
            if (available > BigDecimal.ZERO) key to available else null
        }.toMap()

    fun parseTickerPrices(resultNode: JsonNode): RawPrices = resultNode
        .properties()
        .mapNotNull { (key, value) ->
            val c = value.path("c")
            if (c.isArray && !c.isEmpty) {
                val price = safeParseBigDecimal(c.get(0).asText())
                if (price > BigDecimal.ZERO) key to price else null
            } else {
                null
            }
        }.toMap()

    fun parseTradeHistory(result: JsonNode, allocations: List<String>): Pair<List<TradeRecord>, Int> {
        val count = result.path(KrakenApiConstants.FIELD_COUNT).asInt(0)
        val tradesNode = result.path(KrakenApiConstants.FIELD_TRADES)
        if (!tradesNode.isObject) return emptyList<TradeRecord>() to count

        val tradesList = mutableListOf<TradeRecord>()
        tradesNode.properties().forEach { (tradeId, tradeNode) ->
            val pair = tradeNode.path(KrakenApiConstants.FIELD_PAIR).asText()
            val type = tradeNode.path(KrakenApiConstants.FIELD_TYPE).asText()
            val time = tradeNode.path(KrakenApiConstants.FIELD_TIME).asDouble()
            val priceStr = tradeNode.path(KrakenApiConstants.FIELD_PRICE).asText()
            val costStr = tradeNode.path(KrakenApiConstants.FIELD_COST).asText()
            val volStr = tradeNode.path(KrakenApiConstants.FIELD_VOL).asText()
            val feeStr = tradeNode.path(KrakenApiConstants.FIELD_FEE).asText()
            val orderTxidNode = tradeNode.path(KrakenApiConstants.FIELD_ORDER_TXID)
            val orderTxid =
                if (orderTxidNode.isMissingNode || orderTxidNode.isNull) {
                    null
                } else {
                    orderTxidNode.asText().ifBlank { null }
                }

            val symbol = Asset.fromTradingPair(pair, allocations) ?: return@forEach

            val timestamp = Instant.ofEpochMilli((time * 1000).toLong())
            val side = type.uppercase()
            val volume = safeParseBigDecimal(volStr, PrecisionConstants.SCALE_CRYPTO)
            val usdAmount = safeParseBigDecimal(costStr, PrecisionConstants.SCALE_USD)

            tradesList.add(
                TradeRecord(
                    timestamp = timestamp,
                    pair = pair,
                    side = side,
                    symbol = symbol,
                    volume = volume,
                    usdAmount = usdAmount,
                    success = true,
                    dryRun = false,
                    price = safeParseBigDecimal(priceStr, PrecisionConstants.SCALE_CRYPTO),
                    fee = safeParseBigDecimal(feeStr, PrecisionConstants.SCALE_FEE),
                    source = TradeSource.API_FILL,
                    orderTxid = orderTxid,
                    tradeId = tradeId.ifBlank { null },
                ),
            )
        }
        return tradesList to count
    }

    fun parseLedgerPage(result: JsonNode, expectedTypes: Set<String>?): Pair<List<LedgerEvent>, Int> {
        val count = result.path(KrakenApiConstants.FIELD_COUNT).asInt(0)
        val ledgerNode = result.path(KrakenApiConstants.FIELD_LEDGERS)
        if (!ledgerNode.isObject) return emptyList<LedgerEvent>() to count

        val ledgerList = mutableListOf<LedgerEvent>()
        ledgerNode.properties().forEach { (ledgerId, entryNode) ->
            val type = entryNode.path(KrakenApiConstants.FIELD_TYPE).asText()
            if (expectedTypes != null && type !in expectedTypes) return@forEach

            val time = entryNode.path(KrakenApiConstants.FIELD_TIME).asDouble()
            val amountStr = entryNode.path(KrakenApiConstants.FIELD_AMOUNT).asText()
            val balanceStr = entryNode.path(KrakenApiConstants.FIELD_BALANCE).asText()
            val parsedBalance = runCatching { BigDecimal(balanceStr) }.getOrNull()
            val feeStr = entryNode.path(KrakenApiConstants.FIELD_FEE).asText()
            val refidNode = entryNode.path(KrakenApiConstants.FIELD_REFID)
            val refid =
                if (refidNode.isMissingNode || refidNode.isNull) {
                    null
                } else {
                    refidNode.asText().ifBlank { null }
                }
            val subtypeNode = entryNode.path(KrakenApiConstants.FIELD_SUBTYPE)
            val subtype =
                if (subtypeNode.isMissingNode || subtypeNode.isNull) {
                    null
                } else {
                    subtypeNode.asText().ifBlank { null }
                }
            val aclassNode = entryNode.path(KrakenApiConstants.FIELD_ACLASS)
            val aclass =
                if (aclassNode.isMissingNode || aclassNode.isNull) {
                    null
                } else {
                    aclassNode.asText().ifBlank { null }
                }

            ledgerList.add(
                LedgerEvent(
                    ledgerId = ledgerId,
                    refid = refid,
                    time = Instant.ofEpochMilli((time * 1000).toLong()),
                    type = type,
                    subtype = subtype,
                    aclass = aclass,
                    asset = Asset.normalizeLedgerAsset(entryNode.path(KrakenApiConstants.FIELD_ASSET).asText()),
                    amount = safeParseBigDecimal(amountStr, PrecisionConstants.SCALE_CRYPTO),
                    fee = safeParseBigDecimal(feeStr, PrecisionConstants.SCALE_LEDGER_FEE),
                    balance = safeParseBigDecimal(balanceStr, PrecisionConstants.SCALE_CRYPTO),
                    hasAuthoritativeBalance = parsedBalance?.signum()?.let { it != 0 } == true,
                ),
            )
        }
        return ledgerList to count
    }

    fun parseOHLC(root: JsonNode, pair: String? = null): List<Pair<Long, BigDecimal>> {
        val resultNode = root.path(KrakenApiConstants.FIELD_RESULT)
        if (!resultNode.isObject) return emptyList()

        val ohlcNode = resultNode.properties().firstOrNull { it.key != KrakenApiConstants.FIELD_LAST }?.value
        if (ohlcNode == null || !ohlcNode.isArray) return emptyList()

        val priceList = mutableListOf<Pair<Long, BigDecimal>>()
        ohlcNode.forEach { entry ->
            if (entry.isArray && entry.size() >= 5) {
                val time = entry.get(0).asLong()
                val closePrice =
                    try {
                        BigDecimal(entry.get(4).asText())
                    } catch (_: Exception) {
                        if (pair == null) {
                            log.warn(
                                "Skipping OHLC entry with unparseable close price: {}",
                                entry.get(4).asText(),
                            )
                        } else {
                            log.warn(
                                "Skipping OHLC entry for {} with unparseable close price: {}",
                                pair,
                                entry.get(4).asText(),
                            )
                        }
                        return@forEach
                    }
                priceList.add(Pair(time, closePrice))
            }
        }
        return priceList
    }
}
