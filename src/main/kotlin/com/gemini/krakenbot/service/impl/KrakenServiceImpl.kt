package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.*
import com.gemini.krakenbot.util.PrecisionConstants
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class KrakenServiceImpl(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient
) : KrakenService {

    private val log = LoggerFactory.getLogger(KrakenServiceImpl::class.java)
    private val apiUrl = KrakenApiConstants.API_URL
    private val nonceGenerator = AtomicLong(System.currentTimeMillis() * 1_000_000L)
    val lastFetchedCount = AtomicInteger(0)

    private val rateLimiter = RateLimiter()

    private suspend fun <T> retryWithFlow(
        actionName: String,
        maxAttempts: Int = 5,
        initialBackoffMs: Long = 2000,
        rateLimitBackoffMs: Long = 10000,
        block: suspend () -> T
    ): T = flow {
        var currentBackoff = initialBackoffMs
        var currentRateLimitBackoff = rateLimitBackoffMs

        repeat(maxAttempts) { attempt ->
            try {
                emit(block())
                return@flow
            } catch (e: Exception) {
                val isRateLimit = e.message?.contains("Rate limit exceeded") == true
                val isLockout = e.message?.contains("Temporary lockout") == true
                val isNetworkOrTransient = e is IOException || e is ResponseException

                if ((isNetworkOrTransient || isRateLimit || isLockout) && attempt < maxAttempts - 1) {
                    val waitTime = when {
                        isLockout -> 15.minutes.inWholeMilliseconds
                        isRateLimit -> currentRateLimitBackoff
                        else -> currentBackoff
                    }
                    log.warn(
                        "Transient failure in {} (attempt {}/{}). Retrying in {}ms... Error: {}",
                        actionName, attempt + 1, maxAttempts, waitTime, e.message
                    )
                    delay(waitTime.milliseconds)

                    if (isRateLimit) {
                        currentRateLimitBackoff *= 2
                    } else if (!isLockout) {
                        currentBackoff *= 2
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
        return response.properties()
            .mapNotNull { (key, value) ->
                val amount = safeParseBigDecimal(value.asText())
                if (amount > BigDecimal.ZERO) key to amount else null
            }
            .toMap()
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices {
        val path = "${KrakenApiConstants.PATH_TICKER}?${KrakenApiConstants.PARAM_PAIR}=$pairs"
        val result = queryPublic(path).path(KrakenApiConstants.FIELD_RESULT)
        return result.properties()
            .mapNotNull { (key, value) ->
                val c = value.path("c")
                if (c.isArray && !c.isEmpty) {
                    val price = safeParseBigDecimal(c.get(0).asText())
                    if (price > BigDecimal.ZERO) key to price else null
                } else {
                    null
                }
            }
            .toMap()
    }

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal
    ): OrderResult {
        val normalizedVolume = volume.setScale(
            PrecisionConstants.SCALE_CRYPTO,
            RoundingMode.HALF_UP
        ).stripTrailingZeros()

        if (configService.getConfig().settings.dryRun) {
            log.info(
                "[DRY RUN] Would execute order: {} {} {} volume={}",
                type,
                side,
                pair,
                normalizedVolume.toPlainString()
            )
            return OrderResult(
                success = true,
                pair = pair,
                side = side,
                volume = normalizedVolume,
                dryRun = true
            )
        }

        val path = KrakenApiConstants.PATH_ADD_ORDER
        val params = mapOf(
            KrakenApiConstants.PARAM_PAIR to pair,
            KrakenApiConstants.PARAM_TYPE to side,
            KrakenApiConstants.PARAM_ORDERTYPE to type,
            KrakenApiConstants.PARAM_VOLUME to normalizedVolume.toPlainString()
        )

        return try {
            val resp = queryPrivate(path, params)
            log.info("Order Executed: {}", resp.toString())
            OrderResult(
                success = true,
                pair = pair,
                side = side,
                volume = normalizedVolume
            )
        } catch (e: Exception) {
            val message = e.message.orEmpty().ifEmpty { e.javaClass.simpleName }
            log.error(
                "Failed to execute order: {} {} {} volume={}",
                type,
                side,
                pair,
                normalizedVolume.toPlainString(),
                e
            )
            OrderResult(
                success = false,
                pair = pair,
                side = side,
                volume = normalizedVolume,
                errorMessage = message
            )
        }
    }

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> {
        if (!configService.getConfig().kraken.isConfigured) {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history fetch.")
            return emptyList()
        }

        val params = mutableMapOf<String, String>()
        if (startSec != null) {
            params[KrakenApiConstants.PARAM_START] = startSec.toString()
        }
        if (offset != null) {
            params[KrakenApiConstants.PARAM_OFS] = offset.toString()
        }

        val result = try {
            queryPrivate(KrakenApiConstants.PATH_TRADES_HISTORY, params)
        } catch (e: Exception) {
            log.error("Failed to query private TradesHistory endpoint", e)
            throw e
        }

        val count = result.path(KrakenApiConstants.FIELD_COUNT).asInt(0)
        lastFetchedCount.set(count)

        val tradesNode = result.path(KrakenApiConstants.FIELD_TRADES)
        if (!tradesNode.isObject) {
            return emptyList()
        }

        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        val tradesList = mutableListOf<TradeRecord>()

        tradesNode.properties().forEach { (_, tradeNode) ->
            val pair = tradeNode.path(KrakenApiConstants.FIELD_PAIR).asText()
            val type = tradeNode.path(KrakenApiConstants.FIELD_TYPE).asText()
            val time = tradeNode.path(KrakenApiConstants.FIELD_TIME).asDouble()
            val priceStr = tradeNode.path(KrakenApiConstants.FIELD_PRICE).asText()
            val costStr = tradeNode.path(KrakenApiConstants.FIELD_COST).asText()
            val volStr = tradeNode.path(KrakenApiConstants.FIELD_VOL).asText()
            val feeStr = tradeNode.path(KrakenApiConstants.FIELD_FEE).asText()

            val symbol = Asset.fromTradingPair(pair, allocations) ?: return@forEach

            val timestamp = Instant.ofEpochMilli((time * 1000).toLong())
            val side = type.uppercase()
            val rawVolume = safeParseBigDecimal(volStr)
            val rawUsdAmount = safeParseBigDecimal(costStr)
            val rawPrice = safeParseBigDecimal(priceStr)
            val rawFee = safeParseBigDecimal(feeStr)
            val volume = rawVolume.setScale(PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
            val usdAmount = rawUsdAmount.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)

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
                    price = rawPrice.setScale(PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP),
                    fee = rawFee.setScale(PrecisionConstants.SCALE_FEE, RoundingMode.HALF_UP)
                )
            )
        }
        return tradesList
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
        val result = try {
            queryPublic(path)
        } catch (e: Exception) {
            log.error("Failed to query public OHLC endpoint for pair $pair", e)
            return emptyList()
        }

        val resultNode = result.path(KrakenApiConstants.FIELD_RESULT)
        if (!resultNode.isObject) {
            return emptyList()
        }

        val ohlcNode = resultNode.properties().firstOrNull { it.key != KrakenApiConstants.FIELD_LAST }?.value

        if (ohlcNode == null || !ohlcNode.isArray) {
            return emptyList()
        }

        val priceList = mutableListOf<Pair<Long, BigDecimal>>()
        ohlcNode.forEach { entry ->
            if (entry.isArray && entry.size() >= 5) {
                val time = entry.get(0).asLong()
                val closePrice = try { BigDecimal(entry.get(4).asText()) } catch (_: Exception) { BigDecimal.ZERO }
                priceList.add(Pair(time, closePrice))
            }
        }
        return priceList
    }

    private suspend fun queryPublic(path: String): JsonNode {
        return retryWithFlow("queryPublic($path)") {
            val responseBody = httpClient.get(apiUrl + path).bodyAsText()
            try {
                val root: JsonNode = objectMapper.readTree(responseBody)
                if (root.has(KrakenApiConstants.FIELD_ERROR) &&
                    !root.path(KrakenApiConstants.FIELD_ERROR).isEmpty
                ) {
                    log.error(
                        "Kraken Public API Error for path {}: {}",
                        path,
                        root.path(KrakenApiConstants.FIELD_ERROR)
                    )
                    throw RuntimeException(
                        "Kraken Public API Error: " +
                                root.path(KrakenApiConstants.FIELD_ERROR).toString()
                    )
                }
                root
            } catch (e: JsonProcessingException) {
                throw RuntimeException("Failed to parse public API response", e)
            }
        }
    }

    private suspend fun queryPrivate(
        path: String,
        data: Map<String, String>
    ): JsonNode {
        val apiKey = configService.getConfig().kraken.apiKey.value
        check(apiKey.isNotBlank()) { "API Key is null" }

        val maxRetries = 5

        return retryWithFlow("queryPrivate($path)") {
            var retryCount = 0
            while (true) {
                val cost = if (path.contains("TradesHistory") || path.contains("Ledgers") || path.contains("ClosedOrders")) 2.0 else 1.0
                rateLimiter.acquireWithCost(cost)

                val nonce = nonceGenerator.incrementAndGet().toString()
                val payload = data.toMutableMap()
                payload[KrakenApiConstants.PARAM_NONCE] = nonce

                val postData = payload.entries.joinToString("&") {
                    "${it.key}=${it.value}"
                }
                val signature = signRequest(path, nonce, postData)

                val responseBody = httpClient.post(apiUrl + path) {
                    header(KrakenApiConstants.HEADER_API_KEY, apiKey)
                    header(KrakenApiConstants.HEADER_API_SIGN, signature)
                    header(KrakenApiConstants.HEADER_CONTENT_TYPE, KrakenApiConstants.CONTENT_TYPE_FORM_URLENCODED)
                    setBody(postData)
                }.bodyAsText()

                try {
                    val root: JsonNode = objectMapper.readTree(responseBody)
                    if (!root.path(KrakenApiConstants.FIELD_ERROR).isEmpty) {
                        val errorMsg = root.path(KrakenApiConstants.FIELD_ERROR).toString()
                        if (errorMsg.contains("Invalid nonce") && retryCount < maxRetries) {
                            val bumpAmount = 100_000_000L * (1L shl retryCount)
                            log.warn(
                                "Invalid nonce detected. Adjusting nonce generator by {} and retrying (Attempt {}/{})",
                                bumpAmount,
                                retryCount + 1,
                                maxRetries
                            )
                            nonceGenerator.addAndGet(bumpAmount)
                            retryCount++
                            continue
                        }
                        throw RuntimeException("Kraken API Error: $errorMsg")
                    }
                    return@retryWithFlow root.path(KrakenApiConstants.FIELD_RESULT)
                } catch (e: JsonProcessingException) {
                    throw RuntimeException(
                        "Failed to parse private API response",
                        e
                    )
                }
            }
            @Suppress("KotlinUnreachableCode")
            throw RuntimeException("Unreachable")
        }
    }

    private fun signRequest(
        path: String,
        nonce: String,
        postData: String
    ): String {
        try {
            val sha2 = MessageDigest.getInstance(KrakenApiConstants.SHA_256)
                .digest((nonce + postData).toByteArray(Charsets.UTF_8))

            val pathBytes = path.toByteArray(Charsets.UTF_8)
            val hmacMessage = pathBytes + sha2

            val mac = Mac.getInstance(KrakenApiConstants.HMAC_SHA512)
            val secretDecoded = Base64.decode(configService.getConfig().kraken.privateKey.value)
            val secretSpec = SecretKeySpec(secretDecoded, KrakenApiConstants.HMAC_SHA512)
            mac.init(secretSpec)

            val sigBytes = mac.doFinal(hmacMessage)
            return Base64.encode(sigBytes)
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign request", e)
        }
    }
}
