package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import io.ktor.client.*
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

    override suspend fun getBalances(): RawBalances {
        val path = "/$apiVersion/private/Balance"
        val response = queryPrivate(path, emptyMap())
        return response.properties()
            .associate { (key, value) ->
                key to value.asDouble()
            }
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices {
        val path = "/$apiVersion/public/Ticker?pair=$pairs"
        val result = queryPublic(path).path("result")
        return result.properties()
            .mapNotNull { (key, value) ->
                val c = value.path("c")
                if (c.isArray && !c.isEmpty) key to c.get(0)
                    .asDouble() else null
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

    private suspend fun queryPublic(path: String): JsonNode {
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
            return root
        } catch (e: JsonProcessingException) {
            throw RuntimeException("Failed to parse public API response", e)
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

        while (true) {
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
                        nonceGenerator.addAndGet(bumpAmount) // jump ahead to resolve collisions
                        retryCount++
                        continue
                    }
                    throw RuntimeException("Kraken API Error: $errorMsg")
                }
                return root.path("result")
            } catch (e: JsonProcessingException) {
                throw RuntimeException(
                    "Failed to parse private API response",
                    e
                )
            }
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
