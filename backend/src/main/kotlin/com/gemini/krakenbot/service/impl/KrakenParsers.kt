package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.JsonNode
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.domain.safeParseBigDecimal
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectTickerRequest
import com.gemini.krakenbot.service.DirectValueEvidence
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

object KrakenParsers {
    private val log = LoggerFactory.getLogger(KrakenParsers::class.java)

    /** Parses one account-wide native TradesHistory cursor response without allocation filtering. */
    fun parseAccountTradePage(
        result: JsonNode,
        startSec: Long?,
        endSec: Long?,
        requestedCursor: String?,
    ): ExchangeHistoryPage<ExchangeTradeHistoryEntry> {
        val tradesNode = result.get(KrakenApiConstants.FIELD_TRADES)
        val rawSize = tradesNode?.takeIf(JsonNode::isObject)?.size() ?: 0
        val count = parseOptionalCount(result)
        val cursor = parseCursor(result)
        val entries = tradesNode?.takeIf(JsonNode::isObject)?.properties()?.map { (tradeId, node) ->
            parseAccountTrade(tradeId, node)
        }.orEmpty()

        return ExchangeHistoryPage(
            entries = entries,
            startSec = startSec,
            endSec = endSec,
            requestedCursor = requestedCursor,
            nextCursor = cursor.next,
            count = count.value,
            rawSize = rawSize,
            valid = result.isObject && tradesNode?.isObject == true && count.valid && cursor.valid,
        )
    }

    /** Parses one account-wide native Ledgers cursor response without asset/type filtering. */
    fun parseAccountLedgerPage(
        result: JsonNode,
        startSec: Long?,
        endSec: Long?,
        requestedCursor: String?,
    ): ExchangeHistoryPage<ExchangeLedgerHistoryEntry> {
        val ledgerNode = result.get(KrakenApiConstants.FIELD_LEDGERS)
        val rawSize = ledgerNode?.takeIf(JsonNode::isObject)?.size() ?: 0
        val count = parseOptionalCount(result)
        val cursor = parseCursor(result)
        val entries = ledgerNode?.takeIf(JsonNode::isObject)?.properties()?.map { (ledgerId, node) ->
            parseAccountLedger(ledgerId, node)
        }.orEmpty()

        return ExchangeHistoryPage(
            entries = entries,
            startSec = startSec,
            endSec = endSec,
            requestedCursor = requestedCursor,
            nextCursor = cursor.next,
            count = count.value,
            rawSize = rawSize,
            valid = result.isObject && ledgerNode?.isObject == true && count.valid && cursor.valid,
        )
    }

    private fun parseAccountTrade(tradeId: String, node: JsonNode): ExchangeTradeHistoryEntry {
        val rawTime = rawValue(node, KrakenApiConstants.FIELD_TIME)
        val time = rawTime?.toBigDecimalOrNull()?.let(::epochSecondsToInstantExact)
        val pair = rawValue(node, KrakenApiConstants.FIELD_PAIR)
        val pairSplit = pair?.let { Asset.splitTradingPair(it) }
        val price = rawValue(node, KrakenApiConstants.FIELD_PRICE)
        val cost = rawValue(node, KrakenApiConstants.FIELD_COST)
        val fee = rawValue(node, KrakenApiConstants.FIELD_FEE)
        val volume = rawValue(node, KrakenApiConstants.FIELD_VOL)
        val orderTxid = rawValue(node, KrakenApiConstants.FIELD_ORDER_TXID)
        val ledgerNode = node.get(KrakenApiConstants.FIELD_LEDGER_IDS)
        val ledgerIds = when {
            ledgerNode == null || ledgerNode.isNull -> null
            ledgerNode.isArray -> ledgerNode.mapNotNull(JsonNode::textValue)
            else -> listOfNotNull(rawValue(node, KrakenApiConstants.FIELD_LEDGER_IDS))
        }

        return ExchangeTradeHistoryEntry(
            tradeId = tradeId,
            orderTxid = orderTxid,
            postTxid = rawValue(node, KrakenApiConstants.FIELD_POST_TXID),
            pair = pair,
            baseAsset = pairSplit?.base,
            quoteCurrency = pairSplit?.quote,
            feeCurrency = null,
            time = time,
            rawTime = rawTime,
            type = rawValue(node, KrakenApiConstants.FIELD_TYPE),
            orderType = rawValue(node, KrakenApiConstants.FIELD_ORDER_TYPE),
            price = price,
            cost = cost,
            fee = fee,
            volume = volume,
            margin = rawValue(node, KrakenApiConstants.FIELD_MARGIN),
            misc = rawValue(node, KrakenApiConstants.FIELD_MISC),
            tradeSequenceId = rawValue(node, KrakenApiConstants.FIELD_TRADE_ID),
            maker = node.get(KrakenApiConstants.FIELD_MAKER)?.takeIf(JsonNode::isBoolean)?.booleanValue(),
            ledgerIds = ledgerIds,
            rawRecordJson = node.toString(),
            hasValidTime = time != null,
            hasValidPrice = price.isPositiveDecimal(),
            hasValidCost = cost.isNonNegativeDecimal(),
            hasValidFee = fee.isNonNegativeDecimal(),
            hasValidVolume = volume.isPositiveDecimal(),
            isValidRecord = node.isObject,
        )
    }

    private fun parseAccountLedger(ledgerId: String, node: JsonNode): ExchangeLedgerHistoryEntry {
        val rawTime = rawValue(node, KrakenApiConstants.FIELD_TIME)
        val time = rawTime?.toBigDecimalOrNull()?.let(::epochSecondsToInstantExact)
        val amount = rawValue(node, KrakenApiConstants.FIELD_AMOUNT)
        val fee = rawValue(node, KrakenApiConstants.FIELD_FEE)
        val balance = rawValue(node, KrakenApiConstants.FIELD_BALANCE)

        return ExchangeLedgerHistoryEntry(
            ledgerId = ledgerId,
            refid = rawValue(node, KrakenApiConstants.FIELD_REFID),
            time = time,
            rawTime = rawTime,
            type = rawValue(node, KrakenApiConstants.FIELD_TYPE),
            subtype = rawValue(node, KrakenApiConstants.FIELD_SUBTYPE),
            assetClass = rawValue(node, KrakenApiConstants.FIELD_ACLASS),
            asset = rawValue(node, KrakenApiConstants.FIELD_ASSET),
            amount = amount,
            fee = fee,
            balance = balance,
            rawRecordJson = node.toString(),
            hasValidTime = time != null,
            hasValidAmount = amount.isDecimal(),
            hasValidFee = fee.isNonNegativeDecimal(),
            hasValidBalance = balance.isDecimal(),
            isValidRecord = node.isObject,
        )
    }

    private class ParsedCount(val value: Long?, val valid: Boolean)

    private fun parseOptionalCount(result: JsonNode): ParsedCount {
        val node = result.get(KrakenApiConstants.FIELD_COUNT) ?: return ParsedCount(null, true)
        if (node.isNull) return ParsedCount(null, true)
        val value = node.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        return if (value != null && value >= 0L) ParsedCount(value, true) else ParsedCount(null, false)
    }

    private class ParsedCursor(val next: String?, val valid: Boolean)

    private fun parseCursor(result: JsonNode): ParsedCursor {
        val cursorNode = result.get(KrakenApiConstants.FIELD_CURSOR) ?: return ParsedCursor(null, true)
        if (!cursorNode.isObject) return ParsedCursor(null, false)
        val nextNode = cursorNode.get(KrakenApiConstants.FIELD_NEXT) ?: return ParsedCursor(null, true)
        if (nextNode.isNull) return ParsedCursor(null, true)
        val next = nextNode.takeIf(JsonNode::isTextual)?.textValue()?.takeIf(String::isNotBlank)
        return if (next != null) ParsedCursor(next, true) else ParsedCursor(null, false)
    }

    private fun rawValue(node: JsonNode, field: String): String? {
        val value = node.get(field)?.takeUnless(JsonNode::isNull) ?: return null
        return when {
            value.isValueNode -> value.asText()
            else -> value.toString()
        }
    }

    private fun String?.isDecimal(): Boolean = this?.toBigDecimalOrNull() != null

    private fun String?.isPositiveDecimal(): Boolean = this?.toBigDecimalOrNull()?.signum()?.let { it > 0 } == true

    private fun String?.isNonNegativeDecimal(): Boolean = this?.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true

    /** Converts native decimal epoch seconds without routing through binary floating point or milliseconds. */
    private fun epochSecondsToInstantExact(seconds: BigDecimal): Instant? = runCatching {
        require(seconds.signum() >= 0)
        val wholeSeconds = seconds.setScale(0, RoundingMode.FLOOR).longValueExact()
        val fractionalNanos = seconds
            .subtract(BigDecimal.valueOf(wholeSeconds))
            .movePointRight(9)
            .longValueExact()
        Instant.ofEpochSecond(wholeSeconds, fractionalNanos)
    }.getOrNull()

    fun parseBalances(result: JsonNode): RawBalances = result
        .properties()
        .mapNotNull { (key, value) ->
            val amount = safeParseBigDecimal(value.asText())
            if (amount > BigDecimal.ZERO) key to amount else null
        }.toMap()

    /** Parses a direct Balance response without collapsing absent, zero, or malformed values. */
    fun parseDirectBalanceCapture(
        result: JsonNode,
        requestStartedAt: Instant,
        responseEndedAt: Instant,
    ): DirectBalanceCapture {
        val resultShapeValid = result.isObject
        val values = if (resultShapeValid) {
            result.properties().associate { (assetId, value) ->
                assetId to parseDirectNumericEvidence(value, allowZero = true)
            }
        } else {
            emptyMap()
        }
        return DirectBalanceCapture(
            requestStartedAt = requestStartedAt,
            responseEndedAt = responseEndedAt,
            resultShapeValid = resultShapeValid,
            valuesByAssetId = values,
        )
    }

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

    /** Parses direct last-trade marks while preserving every requested symbol, including gaps. */
    fun parseDirectTickerCapture(
        resultNode: JsonNode,
        requests: List<DirectTickerRequest>,
        requestStartedAt: Instant,
        responseEndedAt: Instant,
    ): DirectTickerCapture {
        val resultShapeValid = resultNode.isObject
        val responsePairs = if (resultShapeValid) resultNode.properties().toList() else emptyList()
        val groupedRequests = requests.groupBy { Asset.canonicalSymbol(it.symbol) }
        val marks = groupedRequests.mapValues { (symbol, symbolRequests) ->
            val requestedPairs = symbolRequests.map { it.pair }.distinct()
            val requestedPair = requestedPairs.joinToString(",")
            val candidates = responsePairs.filter { (responsePair, _) ->
                Asset.matchesUsdQuotedPair(responsePair, symbol)
            }
            val candidateKeys = candidates.map { it.key }
            val candidateValues = candidates.associate { (responsePair, ticker) ->
                responsePair to parseTickerLastTradeEvidence(ticker)
            }

            when {
                !resultShapeValid -> DirectTickerMark(
                    symbol = symbol,
                    requestedPair = requestedPair,
                    responsePair = null,
                    candidateResponsePairs = emptyList(),
                    candidateValuesByPair = emptyMap(),
                    rawPrice = null,
                    price = null,
                    status = DirectEvidenceStatus.MALFORMED,
                )

                symbolRequests.size > 1 || candidates.size > 1 -> DirectTickerMark(
                    symbol = symbol,
                    requestedPair = requestedPair,
                    responsePair = null,
                    candidateResponsePairs = candidateKeys,
                    candidateValuesByPair = candidateValues,
                    rawPrice = null,
                    price = null,
                    status = DirectEvidenceStatus.AMBIGUOUS,
                )

                candidates.isEmpty() -> DirectTickerMark(
                    symbol = symbol,
                    requestedPair = requestedPair,
                    responsePair = null,
                    candidateResponsePairs = emptyList(),
                    candidateValuesByPair = emptyMap(),
                    rawPrice = null,
                    price = null,
                    status = DirectEvidenceStatus.MISSING,
                )

                else -> {
                    val (responsePair, ticker) = candidates.single()
                    val lastTradePrice = candidateValues.getValue(responsePair)
                    DirectTickerMark(
                        symbol = symbol,
                        requestedPair = requestedPair,
                        responsePair = responsePair,
                        candidateResponsePairs = candidateKeys,
                        candidateValuesByPair = candidateValues,
                        rawPrice = lastTradePrice.rawValue,
                        price = lastTradePrice.value,
                        status = lastTradePrice.status,
                    )
                }
            }
        }
        return DirectTickerCapture(
            requestStartedAt = requestStartedAt,
            responseEndedAt = responseEndedAt,
            resultShapeValid = resultShapeValid,
            marksBySymbol = marks,
        )
    }

    private fun parseTickerLastTradeEvidence(ticker: JsonNode): DirectValueEvidence {
        if (!ticker.isObject) {
            return DirectValueEvidence(
                rawValue = ticker.toString(),
                value = null,
                status = DirectEvidenceStatus.MALFORMED,
            )
        }
        val close = ticker.get("c")
        return when {
            close == null || close.isNull -> DirectValueEvidence(
                rawValue = null,
                value = null,
                status = DirectEvidenceStatus.MISSING,
            )

            !close.isArray -> DirectValueEvidence(
                rawValue = close.toString(),
                value = null,
                status = DirectEvidenceStatus.MALFORMED,
            )

            close.isEmpty -> DirectValueEvidence(
                rawValue = null,
                value = null,
                status = DirectEvidenceStatus.MISSING,
            )

            else -> parseDirectNumericEvidence(close[0], allowZero = false)
        }
    }

    private fun parseDirectNumericEvidence(value: JsonNode, allowZero: Boolean): DirectValueEvidence {
        val hasNumericText = value.isTextual || value.isNumber
        val rawValue = if (hasNumericText) value.asText() else value.toString()
        val decimal = if (hasNumericText) runCatching { BigDecimal(rawValue) }.getOrNull() else null
        val status = when {
            decimal == null -> DirectEvidenceStatus.MALFORMED
            allowZero && decimal.signum() < 0 -> DirectEvidenceStatus.NEGATIVE
            !allowZero && decimal.signum() <= 0 -> DirectEvidenceStatus.NON_POSITIVE
            else -> DirectEvidenceStatus.VALID
        }
        return DirectValueEvidence(rawValue = rawValue, value = decimal, status = status)
    }

    fun parseAssetMetadata(response: JsonNode): List<KrakenAssetMetadata> {
        val assets = response.path(KrakenApiConstants.FIELD_RESULT)
        if (!assets.isObject) return emptyList()

        return assets.properties().mapNotNull { (assetId, metadata) ->
            if (!metadata.isObject || assetId.isBlank()) return@mapNotNull null
            val assetClass = metadata.path(KrakenApiConstants.FIELD_ACLASS)
            if (!assetClass.isTextual || assetClass.asText().isBlank()) return@mapNotNull null
            KrakenAssetMetadata(assetId, assetClass.asText())
        }
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
