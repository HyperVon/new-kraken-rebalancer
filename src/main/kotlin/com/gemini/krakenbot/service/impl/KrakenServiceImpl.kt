package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import java.time.Instant
import io.ktor.client.*
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

class KrakenServiceImpl(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient
) : KrakenService {

    private val log =
        LoggerFactory.getLogger(KrakenServiceImpl::class.java)
    private val apiUrl = "https://api.kraken.com"
    private val apiVersion = "0"
    private val nonceGenerator =
        AtomicLong(System.currentTimeMillis() * 1000)
    private val lastPrivateCallTime = AtomicLong(0)
    val lastFetchedCount = java.util.concurrent.atomic.AtomicInteger(0)

    override suspend fun getBalances(): RawBalances {
        val path = "/$apiVersion/private/Balance"
        val response = queryPrivate(path, emptyMap())
        return response.properties()
            .associate { (key, value) ->
                key to BigDecimal(value.asText())
            }
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices {
        val path = "/$apiVersion/public/Ticker?pair=$pairs"
        val result = queryPublic(path).path("result")
        return result.properties()
            .mapNotNull { (key, value) ->
                val c = value.path("c")
                if (c.isArray && !c.isEmpty) key to BigDecimal(c.get(0).asText()) else null
            }
            .toMap()
    }

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal
    ): OrderResult {
        val normalizedVolume =
            volume.setScale(
                8,
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

        val path = "/$apiVersion/private/AddOrder"
        val params = mapOf(
            "pair" to pair,
            "type" to side,
            "ordertype" to type,
            "volume" to normalizedVolume.toPlainString()
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
        val apiKey = configService.getConfig().kraken.apiKey.value
        if (apiKey.isBlank() || apiKey == "YOUR_KRAKEN_API_KEY") {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history fetch.")
            return emptyList()
        }

        val params = mutableMapOf<String, String>()
        if (startSec != null) {
            params["start"] = startSec.toString()
        }
        if (offset != null) {
            params["ofs"] = offset.toString()
        }

        val result = try {
            queryPrivate("/0/private/TradesHistory", params)
        } catch (e: Exception) {
            log.error("Failed to query private TradesHistory endpoint", e)
            throw e
        }

        val count = result.path("count").asInt(0)
        lastFetchedCount.set(count)

        val tradesNode = result.path("trades")
        if (!tradesNode.isObject) {
            return emptyList()
        }

        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        val tradesList = mutableListOf<TradeRecord>()

        tradesNode.properties().forEach { (_, tradeNode) ->
            val pair = tradeNode.path("pair").asText()
            val type = tradeNode.path("type").asText() // "buy" or "sell"
            val time = tradeNode.path("time").asDouble() // e.g. 1618000000.1234
            val priceStr = tradeNode.path("price").asText()
            val costStr = tradeNode.path("cost").asText()
            val volStr = tradeNode.path("vol").asText()
            val feeStr = tradeNode.path("fee").asText()

            // Map pair back to standard symbol using consolidated logic
            val symbol = Asset.fromTradingPair(pair, allocations) ?: return@forEach

            val timestamp = Instant.ofEpochMilli((time * 1000).toLong())
            val side = type.uppercase() // "BUY" or "SELL"
            val rawVolume = try { BigDecimal(volStr) } catch (e: Exception) { BigDecimal.ZERO }
            val rawUsdAmount = try { BigDecimal(costStr) } catch (e: Exception) { BigDecimal.ZERO }
            val rawPrice = try { BigDecimal(priceStr) } catch (e: Exception) { BigDecimal.ZERO }
            val rawFee = try { BigDecimal(feeStr) } catch (e: Exception) { BigDecimal.ZERO }
            val volume = rawVolume.setScale(8, RoundingMode.HALF_UP)
            val usdAmount = rawUsdAmount.setScale(2, RoundingMode.HALF_UP)

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
                    price = rawPrice.setScale(8, RoundingMode.HALF_UP),
                    fee = rawFee.setScale(4, RoundingMode.HALF_UP)
                )
            )
        }
        return tradesList
    }

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> {
        val params = mutableMapOf<String, String>()
        params["pair"] = pair
        params["interval"] = interval.toString()
        if (since != null) {
            params["since"] = since.toString()
        }
        val queryStr = params.map { "${it.key}=${it.value}" }.joinToString("&")
        val path = "/0/public/OHLC?$queryStr"
        val result = try {
            queryPublic(path)
        } catch (e: Exception) {
            log.error("Failed to query public OHLC endpoint for pair $pair", e)
            return emptyList()
        }

        val resultNode = result.path("result")
        if (!resultNode.isObject) {
            return emptyList()
        }

        val ohlcNode = resultNode.properties().firstOrNull { it.key != "last" }?.value

        if (ohlcNode == null || !ohlcNode.isArray) {
            return emptyList()
        }

        val priceList = mutableListOf<Pair<Long, BigDecimal>>()
        ohlcNode.forEach { entry ->
            if (entry.isArray && entry.size() >= 5) {
                val time = entry.get(0).asLong()
                val closePrice = try { BigDecimal(entry.get(4).asText()) } catch (e: Exception) { BigDecimal.ZERO }
                priceList.add(Pair(time, closePrice))
            }
        }
        return priceList
    }


    private suspend fun <T> retryOnTransientFailure(
        actionName: String,
        block: suspend () -> T
    ): T {
        var attempt = 0
        val maxAttempts = 5
        var backoffMs = 2000L
        var rateLimitBackoffMs = 10000L

        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                val isRateLimit = e.message?.contains("Rate limit exceeded") == true
                val isNetworkOrTransient = e is IOException ||
                                           e is ResponseException

                if ((isNetworkOrTransient || isRateLimit) && attempt < maxAttempts - 1) {
                    attempt++
                    val currentBackoff = if (isRateLimit) rateLimitBackoffMs else backoffMs
                    log.warn("Transient failure in {} (attempt {}/{}). Retrying in {}ms... Error: {}",
                        actionName, attempt, maxAttempts, currentBackoff, e.message)
                    delay(currentBackoff.milliseconds)
                    if (isRateLimit) {
                        rateLimitBackoffMs *= 2
                    } else {
                        backoffMs *= 2
                    }
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun queryPublic(path: String): JsonNode {
        return retryOnTransientFailure("queryPublic($path)") {
            val responseBody = httpClient.get(apiUrl + path).bodyAsText()
            try {
                val root: JsonNode = objectMapper.readTree(responseBody)
                if (root.has("error") &&
                    !root.path("error").isEmpty
                ) {
                    log.error(
                        "Kraken Public API Error for path {}: {}",
                        path,
                        root.path("error")
                    )
                    throw RuntimeException(
                        "Kraken Public API Error: " +
                                root.path("error").toString()
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
        var retryCount = 0

        return retryOnTransientFailure("queryPrivate($path)") {
            while (true) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastPrivateCallTime.get()
                if (elapsed < 1000L) {
                    delay((1000L - elapsed).milliseconds)
                }
                lastPrivateCallTime.set(System.currentTimeMillis())

                val nonce = nonceGenerator.incrementAndGet().toString()
                val payload = data.toMutableMap()
                payload["nonce"] = nonce

                val postData =
                    payload.entries.joinToString("&") {
                        "${it.key}=${it.value}"
                    }
                val signature = signRequest(path, nonce, postData)

                val responseBody = httpClient.post(apiUrl + path) {
                    header("API-Key", apiKey)
                    header("API-Sign", signature)
                    header("Content-Type", "application/x-www-form-urlencoded")
                    setBody(postData)
                }.bodyAsText()

                try {
                    val root: JsonNode = objectMapper.readTree(responseBody)
                    if (!root.path("error").isEmpty) {
                        val errorMsg = root.path("error").toString()
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
                    return@retryOnTransientFailure root.path("result")
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
            val sha2 = MessageDigest.getInstance(SHA_256)
                .digest((nonce + postData).toByteArray(Charsets.UTF_8))

            val pathBytes = path.toByteArray(Charsets.UTF_8)
            val hmacMessage = pathBytes + sha2

            val mac = Mac.getInstance(HMAC_SHA512)
            val secretDecoded =
                Base64.decode(configService.getConfig().kraken.privateKey.value)
            val secretSpec =
                SecretKeySpec(secretDecoded, HMAC_SHA512)
            mac.init(secretSpec)

            val sigBytes = mac.doFinal(hmacMessage)
            return Base64.encode(sigBytes)
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign request", e)
        }
    }

    private companion object {
        const val HMAC_SHA512 = "HmacSHA512"
        const val SHA_256 = "SHA-256"
    }
}
