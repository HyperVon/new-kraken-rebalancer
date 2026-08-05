package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.service.BoundedTradeHistoryService
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import com.gemini.krakenbot.service.safeParseBigDecimal
import com.gemini.krakenbot.util.PrecisionConstants
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Call-counter cost for a private Kraken path (heavy history endpoints cost 2.0). */
internal fun krakenPrivateEndpointCost(path: String): Double = when {
    path.contains(KrakenApiConstants.SUBSTRING_TRADES_HISTORY) ||
        path.contains(KrakenApiConstants.SUBSTRING_LEDGERS) ||
        path.contains(KrakenApiConstants.SUBSTRING_CLOSED_ORDERS) -> 2.0
    else -> 1.0
}

class KrakenServiceImpl(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter = RateLimiter(),
) : KrakenService,
    BoundedTradeHistoryService {
    private class AmbiguousOrderSubmissionException(message: String) : RuntimeException(message)

    private val log = LoggerFactory.getLogger(KrakenServiceImpl::class.java)
    private val apiUrl = KrakenApiConstants.API_URL

    // Kraken rejects any nonce that is not strictly increasing. Seeding from millis×1e6 leaves room
    // for many nonces inside one millisecond while staying time-derived, so a normal restart does not
    // rewind (NTP/clock rollback could still seed lower).
    private val nonceGenerator = AtomicLong(System.currentTimeMillis() * 1_000_000L)

    /** Total trade count from the last TradesHistory response (Kraken `count`); used for pagination. */
    private val lastFetchedCount = AtomicInteger(0)

    /** Total ledger entry count from the last Ledgers response (Kraken `count`); used for pagination. */
    private val lastLedgerCount = AtomicInteger(0)

    override fun getLastTradeHistoryTotalCount(): Int = lastFetchedCount.get()

    override fun getLastLedgerTotalCount(): Int = lastLedgerCount.get()

    override suspend fun getApiCallCounter(): Double = rateLimiter.getCurrentCounter()

    private suspend fun <T> retryWithFlow(
        actionName: String,
        maxAttempts: Int = 5,
        // Enough retries for 10s → 15min lockout doubling to reach the ceiling.
        maxLockoutAttempts: Int = 9,
        initialBackoffMs: Long = 2000,
        rateLimitBackoffMs: Long = 10000,
        initialLockoutBackoffMs: Long = 10_000,
        maxLockoutBackoffMs: Long = 15.minutes.inWholeMilliseconds,
        block: suspend () -> T,
    ): T = flow {
        var currentBackoff = initialBackoffMs
        var currentRateLimitBackoff = rateLimitBackoffMs
        var currentLockoutBackoff = initialLockoutBackoffMs
        // Lockouts use a separate attempt budget so a long lockout ladder does not burn network retries.
        var attempt = 0
        var lockoutAttempt = 0

        while (true) {
            try {
                emit(block())
                return@flow
            } catch (e: Exception) {
                val isRateLimit = e.message?.contains(KrakenApiConstants.ERROR_RATE_LIMIT_EXCEEDED) == true
                val isLockout = e.message?.contains(KrakenApiConstants.ERROR_TEMPORARY_LOCKOUT) == true
                val isNetworkOrTransient = e is IOException || e is ResponseException
                val retryable = isNetworkOrTransient || isRateLimit || isLockout
                val attemptsUsed = if (isLockout) lockoutAttempt else attempt
                val attemptLimit = if (isLockout) maxLockoutAttempts else maxAttempts

                if (retryable && attemptsUsed < attemptLimit - 1) {
                    val waitTime =
                        when {
                            isLockout -> currentLockoutBackoff.coerceAtMost(maxLockoutBackoffMs)
                            isRateLimit -> currentRateLimitBackoff
                            else -> currentBackoff
                        }
                    log.warn(
                        "Transient failure in {} (attempt {}/{}). Retrying in {}ms... Error: {}",
                        actionName,
                        attemptsUsed + 1,
                        attemptLimit,
                        waitTime,
                        e.message,
                    )
                    delay(waitTime.milliseconds)

                    when {
                        isLockout -> {
                            currentLockoutBackoff =
                                (currentLockoutBackoff * 2).coerceAtMost(maxLockoutBackoffMs)
                            lockoutAttempt++
                        }
                        isRateLimit -> {
                            currentRateLimitBackoff *= 2
                            attempt++
                        }
                        else -> {
                            currentBackoff *= 2
                            attempt++
                        }
                    }
                } else {
                    throw e
                }
            }
        }
    }.first()

    override suspend fun getBalances(): RawBalances {
        val path = KrakenApiConstants.PATH_BALANCE
        val response = queryPrivate(path, emptyMap())
        return response
            .properties()
            .mapNotNull { (key, value) ->
                val amount = safeParseBigDecimal(value.asText())
                if (amount > BigDecimal.ZERO) key to amount else null
            }.toMap()
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices {
        val path = "${KrakenApiConstants.PATH_TICKER}?${KrakenApiConstants.PARAM_PAIR}=$pairs"
        val result = queryPublic(path).path(KrakenApiConstants.FIELD_RESULT)
        return result
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
    }

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean?,
        clOrdId: String?,
    ): OrderResult {
        val normalizedVolume =
            volume
                .setScale(
                    PrecisionConstants.SCALE_CRYPTO,
                    RoundingMode.HALF_UP,
                ).stripTrailingZeros()

        val isDryRun = dryRun ?: configService.getConfig().settings.dryRun
        if (isDryRun) {
            log.info(
                "[DRY RUN] Would execute order: {} {} {} volume={} cl_ord_id={}",
                type,
                side,
                pair,
                normalizedVolume.toPlainString(),
                clOrdId,
            )
            return OrderResult(
                success = true,
                pair = pair,
                side = side,
                volume = normalizedVolume,
                dryRun = true,
            )
        }

        val path = KrakenApiConstants.PATH_ADD_ORDER
        val params =
            mutableMapOf(
                KrakenApiConstants.PARAM_PAIR to pair,
                KrakenApiConstants.PARAM_TYPE to side,
                KrakenApiConstants.PARAM_ORDERTYPE to type,
                KrakenApiConstants.PARAM_VOLUME to normalizedVolume.toPlainString(),
            )
        if (clOrdId != null) {
            params[KrakenApiConstants.PARAM_CL_ORD_ID] = clOrdId
        }

        return try {
            val resp = queryPrivate(path, params)
            val txidNode = resp.path(KrakenApiConstants.FIELD_TXID)
            val orderTxid =
                if (txidNode.isArray && txidNode.size() > 0) {
                    txidNode[0].asText().ifBlank { null }
                } else {
                    null
                }
            log.info(
                "Order executed pair={} side={} volume={} txid={}",
                pair,
                side,
                normalizedVolume.toPlainString(),
                orderTxid,
            )
            if (orderTxid == null) {
                OrderResult(
                    success = false,
                    pair = pair,
                    side = side,
                    volume = normalizedVolume,
                    errorMessage = "Kraken AddOrder response did not contain a transaction id",
                    submissionUncertain = true,
                )
            } else {
                OrderResult(
                    success = true,
                    pair = pair,
                    side = side,
                    volume = normalizedVolume,
                    orderTxid = orderTxid,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message.orEmpty().ifEmpty { e.javaClass.simpleName }
            log.error(
                "Failed to execute order: {} {} {} volume={}",
                type,
                side,
                pair,
                normalizedVolume.toPlainString(),
                e,
            )
            OrderResult(
                success = false,
                pair = pair,
                side = side,
                volume = normalizedVolume,
                errorMessage = message,
                submissionUncertain = isAmbiguousSubmissionFailure(e),
            )
        }
    }

    private fun isAmbiguousSubmissionFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any { cause ->
            cause is AmbiguousOrderSubmissionException ||
                cause is IOException ||
                cause is ResponseException ||
                cause is JsonProcessingException
        }

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> =
        getTradeHistoryUntil(startSec, offset, null)

    override suspend fun getTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord> {
        if (!configService.getConfig().kraken.hasValidCredentials()) {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history fetch.")
            return emptyList()
        }

        val params = mutableMapOf<String, String>()
        if (startSec != null) {
            params[KrakenApiConstants.PARAM_START] = startSec.toString()
        }
        if (endSec != null) {
            params[KrakenApiConstants.PARAM_END] = endSec.toString()
        }
        if (offset != null) {
            params[KrakenApiConstants.PARAM_OFS] = offset.toString()
        }

        val result =
            try {
                queryPrivate(KrakenApiConstants.PATH_TRADES_HISTORY, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to query private TradesHistory endpoint", e)
                throw e
            }

        lastFetchedCount.set(result.path(KrakenApiConstants.FIELD_COUNT).asInt(0))

        val tradesNode = result.path(KrakenApiConstants.FIELD_TRADES)
        if (!tradesNode.isObject) {
            return emptyList()
        }

        val allocations = configService.getConfig().allocations.map { it.symbol.value }
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
        return tradesList
    }

    override suspend fun getLedgers(
        startSec: Long?,
        offset: Int?,
        endSec: Long?,
        types: Set<String>?,
    ): List<LedgerEvent> {
        if (!configService.getConfig().kraken.hasValidCredentials()) {
            log.warn("Kraken API key is blank or placeholder. Skipping ledger fetch.")
            return emptyList()
        }

        val params = mutableMapOf<String, String>()
        if (startSec != null) {
            params[KrakenApiConstants.PARAM_START] = startSec.toString()
        }
        if (endSec != null) {
            params[KrakenApiConstants.PARAM_END] = endSec.toString()
        }
        if (offset != null) {
            params[KrakenApiConstants.PARAM_OFS] = offset.toString()
        }

        val result =
            try {
                queryPrivate(KrakenApiConstants.PATH_LEDGERS, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to query private Ledgers endpoint", e)
                throw e
            }

        lastLedgerCount.set(result.path(KrakenApiConstants.FIELD_COUNT).asInt(0))

        val ledgerNode = result.path(KrakenApiConstants.FIELD_LEDGERS)
        if (!ledgerNode.isObject) {
            return emptyList()
        }

        val ledgerList = mutableListOf<LedgerEvent>()
        ledgerNode.properties().forEach { (refid, entryNode) ->
            val type = entryNode.path(KrakenApiConstants.FIELD_TYPE).asText()
            if (types != null && type !in types) {
                return@forEach
            }

            val time = entryNode.path(KrakenApiConstants.FIELD_TIME).asDouble()
            val amountStr = entryNode.path(KrakenApiConstants.FIELD_AMOUNT).asText()
            val balanceStr = entryNode.path(KrakenApiConstants.FIELD_BALANCE).asText()
            val feeStr = entryNode.path(KrakenApiConstants.FIELD_FEE).asText()
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
                    refid = refid,
                    time = Instant.ofEpochMilli((time * 1000).toLong()),
                    type = type,
                    subtype = subtype,
                    aclass = aclass,
                    asset = entryNode.path(KrakenApiConstants.FIELD_ASSET).asText(),
                    amount = safeParseBigDecimal(amountStr, PrecisionConstants.SCALE_CRYPTO),
                    fee = safeParseBigDecimal(feeStr, PrecisionConstants.SCALE_FEE),
                    balance = safeParseBigDecimal(balanceStr, PrecisionConstants.SCALE_CRYPTO),
                ),
            )
        }
        return ledgerList
    }

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> {
        val params = mutableMapOf<String, String>()
        params[KrakenApiConstants.PARAM_PAIR] = pair
        params[KrakenApiConstants.PARAM_INTERVAL] = interval.toString()
        if (since != null) {
            params[KrakenApiConstants.PARAM_SINCE] = since.toString()
        }
        val queryStr = params.map { "${it.key}=${it.value}" }.joinToString("&")
        val path = "${KrakenApiConstants.PATH_OHLC}?$queryStr"
        val result =
            try {
                queryPublic(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to query public OHLC endpoint for pair $pair", e)
                return emptyList()
            }

        val resultNode = result.path(KrakenApiConstants.FIELD_RESULT)
        if (!resultNode.isObject) {
            return emptyList()
        }

        // Kraken puts a `last` cursor alongside the candle arrays under `result`; skip that key.
        val ohlcNode = resultNode.properties().firstOrNull { it.key != KrakenApiConstants.FIELD_LAST }?.value

        if (ohlcNode == null || !ohlcNode.isArray) {
            return emptyList()
        }

        val priceList = mutableListOf<Pair<Long, BigDecimal>>()
        ohlcNode.forEach { entry ->
            if (entry.isArray && entry.size() >= 5) {
                val time = entry.get(0).asLong()
                val closePrice =
                    try {
                        BigDecimal(entry.get(4).asText())
                    } catch (_: Exception) {
                        BigDecimal.ZERO
                    }
                priceList.add(Pair(time, closePrice))
            }
        }
        return priceList
    }

    // Public paths: no RateLimiter and no HMAC — only private calls acquire cost / sign.
    private suspend fun queryPublic(path: String): JsonNode = retryWithFlow("queryPublic($path)") {
        val responseBody = httpClient.get(apiUrl + path).bodyAsText()
        try {
            val root: JsonNode = objectMapper.readTree(responseBody)
            if (root.has(KrakenApiConstants.FIELD_ERROR) &&
                !root.path(KrakenApiConstants.FIELD_ERROR).isEmpty
            ) {
                log.error(
                    "Kraken Public API Error for path {}: {}",
                    path,
                    root.path(KrakenApiConstants.FIELD_ERROR),
                )
                throw RuntimeException(
                    KrakenApiConstants.ERROR_PUBLIC_API_PREFIX +
                        root.path(KrakenApiConstants.FIELD_ERROR).toString(),
                )
            }
            root
        } catch (e: JsonProcessingException) {
            throw RuntimeException(KrakenApiConstants.ERROR_PARSE_PUBLIC, e)
        }
    }

    private suspend fun queryPrivate(path: String, data: Map<String, String>): JsonNode {
        val apiKey =
            configService
                .getConfig()
                .kraken.apiKey.value
        check(apiKey.isNotBlank()) { KrakenApiConstants.ERROR_API_KEY_NULL }

        val maxRetries = 5

        return retryWithFlow(
            actionName = "queryPrivate($path)",
            maxAttempts = if (path == KrakenApiConstants.PATH_ADD_ORDER) 1 else 5,
            maxLockoutAttempts = if (path == KrakenApiConstants.PATH_ADD_ORDER) 1 else 9,
        ) {
            var retryCount = 0
            var result: JsonNode? = null
            while (result == null) {
                rateLimiter.acquireWithCost(krakenPrivateEndpointCost(path))

                val nonce = nonceGenerator.incrementAndGet().toString()
                val payload = data.toMutableMap()
                payload[KrakenApiConstants.PARAM_NONCE] = nonce

                val postData =
                    payload.entries.joinToString("&") {
                        "${URLEncoder.encode(it.key, Charsets.UTF_8)}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
                    }
                // Signature / private key must never be logged — only API-Sign header below.
                val signature = signRequest(path, nonce, postData)

                val response =
                    httpClient.post(apiUrl + path) {
                        header(KrakenApiConstants.HEADER_API_KEY, apiKey)
                        header(KrakenApiConstants.HEADER_API_SIGN, signature)
                        header(
                            KrakenApiConstants.HEADER_CONTENT_TYPE,
                            KrakenApiConstants.CONTENT_TYPE_FORM_URLENCODED,
                        )
                        setBody(postData)
                    }
                val responseBody = response.bodyAsText()
                if (!response.status.isSuccess()) {
                    throw ResponseException(response, responseBody)
                }

                try {
                    val root: JsonNode = objectMapper.readTree(responseBody)
                    if (!root.path(KrakenApiConstants.FIELD_ERROR).isEmpty) {
                        val errorMsg = root.path(KrakenApiConstants.FIELD_ERROR).toString()
                        if (errorMsg.contains(KrakenApiConstants.ERROR_INVALID_NONCE) &&
                            path == KrakenApiConstants.PATH_ADD_ORDER
                        ) {
                            throw AmbiguousOrderSubmissionException(
                                "Kraken AddOrder returned Invalid nonce after the single submission attempt",
                            )
                        }
                        if (errorMsg.contains(KrakenApiConstants.ERROR_INVALID_NONCE) && retryCount < maxRetries) {
                            // Exponential bump (1e8, 2e8, 4e8, …) to leap past a stale/server-ahead nonce.
                            val bumpAmount = 100_000_000L * (1L shl retryCount)
                            log.warn(
                                "Invalid nonce detected. Adjusting nonce generator by {} and retrying (Attempt {}/{})",
                                bumpAmount,
                                retryCount + 1,
                                maxRetries,
                            )
                            nonceGenerator.addAndGet(bumpAmount)
                            retryCount++
                            continue
                        }
                        throw RuntimeException("${KrakenApiConstants.ERROR_API_PREFIX}$errorMsg")
                    }
                    result = root.path(KrakenApiConstants.FIELD_RESULT)
                } catch (e: JsonProcessingException) {
                    throw RuntimeException(
                        KrakenApiConstants.ERROR_PARSE_PRIVATE,
                        e,
                    )
                }
            }
            result
        }
    }

    // Kraken: HMAC-SHA512(base64-decoded secret, URI path || SHA256(nonce + postData)), then Base64.
    private fun signRequest(path: String, nonce: String, postData: String): String {
        try {
            val sha2 =
                MessageDigest
                    .getInstance(KrakenApiConstants.SHA_256)
                    .digest((nonce + postData).toByteArray(Charsets.UTF_8))

            val pathBytes = path.toByteArray(Charsets.UTF_8)
            val hmacMessage = pathBytes + sha2

            val mac = Mac.getInstance(KrakenApiConstants.HMAC_SHA512)
            val secretDecoded =
                Base64.decode(
                    configService
                        .getConfig()
                        .kraken.privateKey.value,
                )
            val secretSpec = SecretKeySpec(secretDecoded, KrakenApiConstants.HMAC_SHA512)
            mac.init(secretSpec)

            val sigBytes = mac.doFinal(hmacMessage)
            return Base64.encode(sigBytes)
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign request", e)
        }
    }
}
