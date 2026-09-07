package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.JsonNode
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.domain.safeParseBigDecimal
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.model.WithdrawStatusRecord
import com.gemini.krakenbot.util.PrecisionConstants
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant

/** One page of a legacy Kraken funding-status response. */
data class FundingStatusPage<T>(val records: List<T>, val nextCursor: String? = null)

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

    fun parseTradeHistory(
        result: JsonNode,
        allocations: List<String>,
        preserveUnmapped: Boolean = false,
    ): Pair<List<TradeRecord>, Int> {
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

            val symbol = Asset.fromTradingPair(pair, allocations)
                ?: if (preserveUnmapped) {
                    Asset.fromTradingPair(pair, emptyList()) ?: pair.trim().uppercase()
                } else {
                    return@forEach
                }

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

    data class LedgerPageResult(val entries: List<LedgerEvent>, val totalCount: Int, val rawPageSize: Int)

    fun parseLedgerPage(result: JsonNode, expectedTypes: Set<String>?): LedgerPageResult {
        val count = result.path(KrakenApiConstants.FIELD_COUNT).asInt(0)
        val ledgerNode = result.path(KrakenApiConstants.FIELD_LEDGERS)
        if (!ledgerNode.isObject) return LedgerPageResult(emptyList(), count, 0)
        val rawPageSize = ledgerNode.size()

        val ledgerList = mutableListOf<LedgerEvent>()
        ledgerNode.properties().forEach { (ledgerId, entryNode) ->
            val type = entryNode.path(KrakenApiConstants.FIELD_TYPE).asText()
            if (expectedTypes != null && type !in expectedTypes) return@forEach

            val time = entryNode.path(KrakenApiConstants.FIELD_TIME).asDouble()
            val amountStr = entryNode.path(KrakenApiConstants.FIELD_AMOUNT).asText()
            val balanceStr = entryNode.path(KrakenApiConstants.FIELD_BALANCE).asText()
            val parsedBalance = runCatching { BigDecimal(balanceStr) }.getOrNull()
            val scaledBalance = safeParseBigDecimal(balanceStr, PrecisionConstants.SCALE_CRYPTO)
            val feeStr = entryNode.path(KrakenApiConstants.FIELD_FEE).asText()
            val parsedFee = runCatching { BigDecimal(feeStr) }.getOrNull()
            val hasValidFee = feeStr.isBlank() || (parsedFee != null && parsedFee.signum() >= 0)
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
                    // Kraken fees are documented non-negative; clamping at the
                    // trust boundary keeps `amount - fee` from over-crediting
                    // balance deltas. [hasValidFee] ensures accounting still
                    // rejects malformed input instead of treating it as zero.
                    fee = safeParseBigDecimal(feeStr, PrecisionConstants.SCALE_LEDGER_FEE).max(BigDecimal.ZERO),
                    balance = scaledBalance,
                    // An explicit zero is an authoritative post-event
                    // balance; only a missing or malformed field is
                    // non-authoritative.
                    hasAuthoritativeBalance = parsedBalance != null,
                    hasAuthoritativeFee = hasValidFee && parsedFee != null,
                    hasValidFee = hasValidFee,
                ),
            )
        }
        return LedgerPageResult(ledgerList, count, rawPageSize)
    }

    fun parseDepositStatus(result: JsonNode): List<DepositStatusRecord> = parseDepositStatusPage(result).records

    fun parseDepositStatusPage(result: JsonNode): FundingStatusPage<DepositStatusRecord> =
        parseFundingStatusPage(result, KrakenApiConstants.FIELD_DEPOSIT) { node, time, fee, hasFee ->
            DepositStatusRecord(
                refid = node.path(KrakenApiConstants.FIELD_REFID).asText(),
                txid = optionalText(node, KrakenApiConstants.FIELD_TXID),
                asset = Asset.normalizeLedgerAsset(node.path(KrakenApiConstants.FIELD_ASSET).asText()),
                amount = parseRawDecimal(node, KrakenApiConstants.FIELD_AMOUNT) ?: return@parseFundingStatusPage null,
                fee = fee,
                time = time,
                status = node.path(KrakenApiConstants.FIELD_STATUS).asText(),
                method = optionalText(node, KrakenApiConstants.FIELD_METHOD),
                hasAuthoritativeFee = hasFee,
            )
        }

    fun parseWithdrawStatus(result: JsonNode): List<WithdrawStatusRecord> = parseWithdrawStatusPage(result).records

    fun parseWithdrawStatusPage(result: JsonNode): FundingStatusPage<WithdrawStatusRecord> =
        parseFundingStatusPage(result, KrakenApiConstants.FIELD_WITHDRAWAL) { node, time, fee, hasFee ->
            WithdrawStatusRecord(
                refid = node.path(KrakenApiConstants.FIELD_REFID).asText(),
                txid = optionalText(node, KrakenApiConstants.FIELD_TXID),
                asset = Asset.normalizeLedgerAsset(node.path(KrakenApiConstants.FIELD_ASSET).asText()),
                amount = parseRawDecimal(node, KrakenApiConstants.FIELD_AMOUNT) ?: return@parseFundingStatusPage null,
                fee = fee,
                time = time,
                status = node.path(KrakenApiConstants.FIELD_STATUS).asText(),
                method = optionalText(node, KrakenApiConstants.FIELD_METHOD),
                hasAuthoritativeFee = hasFee,
            )
        }

    private fun <T> parseFundingStatusPage(
        result: JsonNode,
        preferredField: String,
        factory: (JsonNode, Instant, BigDecimal, Boolean) -> T?,
    ): FundingStatusPage<T> {
        val resultNode = if (result.isObject && result.has(KrakenApiConstants.FIELD_RESULT)) {
            result.path(KrakenApiConstants.FIELD_RESULT)
        } else {
            result
        }
        val entries = when {
            resultNode.isArray -> resultNode.toList()

            resultNode.path(preferredField).isArray -> resultNode.path(preferredField).toList()

            resultNode.path(KrakenApiConstants.FIELD_DATA).isArray ->
                resultNode.path(KrakenApiConstants.FIELD_DATA).toList()

            else -> emptyList()
        }
        val records = entries.mapNotNull { node ->
            val refid = node.path(KrakenApiConstants.FIELD_REFID).asText()
            val amount = parseRawDecimal(node, KrakenApiConstants.FIELD_AMOUNT)
            val time = parseFundingTime(node)
            if (!node.isObject || refid.isBlank() || amount == null || time == null) {
                null
            } else {
                val feeNode = node.path(KrakenApiConstants.FIELD_FEE)
                val parsedFee = parseRawDecimal(node, KrakenApiConstants.FIELD_FEE)
                val feeRaw = feeNode.asText().trim()
                val hasValidFee = feeRaw.isBlank() || (parsedFee != null && parsedFee.signum() >= 0)
                if (!hasValidFee) {
                    null
                } else {
                    factory(
                        node,
                        time,
                        parsedFee ?: BigDecimal.ZERO,
                        feeNode.isValueNode && parsedFee != null,
                    )
                }
            }
        }
        val cursor = optionalText(resultNode, KrakenApiConstants.FIELD_CURSOR)
        return FundingStatusPage(records = records, nextCursor = cursor)
    }

    private fun parseFundingTime(node: JsonNode): Instant? {
        val seconds = node.path(KrakenApiConstants.FIELD_TIME).asDouble(Double.NaN)
        if (!seconds.isFinite() || seconds < 0) return null
        return runCatching { Instant.ofEpochMilli((seconds * 1000.0).toLong()) }.getOrNull()
    }

    private fun parseRawDecimal(node: JsonNode, field: String): BigDecimal? =
        node.path(field).asText().takeIf(String::isNotBlank)?.let { raw ->
            runCatching { BigDecimal(raw) }.getOrNull()
        }

    private fun optionalText(node: JsonNode, field: String): String? =
        node.path(field).asText().trim().takeIf(String::isNotBlank)

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
