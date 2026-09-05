package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.service.ConfigService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

internal class AmbiguousOrderSubmissionException(message: String) : RuntimeException(message)

/** Kraken rejected an authenticated endpoint because the API key lacks its permission. */
internal class KrakenApiPermissionDeniedException(val endpoint: String, message: String) : RuntimeException(message)

internal fun krakenPrivateEndpointCost(path: String): Double = when {
    path == KrakenApiConstants.PATH_ADD_ORDER || path.endsWith("/CancelOrder") -> 0.0

    path.contains(KrakenApiConstants.SUBSTRING_TRADES_HISTORY) ||
        path.contains(KrakenApiConstants.SUBSTRING_LEDGERS) ||
        path.contains(KrakenApiConstants.SUBSTRING_CLOSED_ORDERS) -> 4.0

    else -> 1.0
}

class KrakenTransport(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
    private val nonceGenerator: AtomicLong,
    private val publicRateLimiter: PublicRateLimiter = PublicRateLimiter(),
) {
    private val log = LoggerFactory.getLogger(KrakenTransport::class.java)
    private val apiUrl = KrakenApiConstants.API_URL
    private val privateRequestMutex = Mutex()

    suspend fun queryPublic(path: String): JsonNode {
        publicRateLimiter.acquire()
        val response = httpClient.get(apiUrl + path)
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw ResponseException(response, responseBody)
        }
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
            return root
        } catch (e: JsonProcessingException) {
            throw RuntimeException(KrakenApiConstants.ERROR_PARSE_PUBLIC, e)
        }
    }

    suspend fun queryPrivate(path: String, data: Map<String, String>): JsonNode {
        val maxRetries = 5
        var retryCount = 0
        while (true) {
            val requestCost = krakenPrivateEndpointCost(path)
            if (requestCost > 0.0) rateLimiter.acquireWithCost(requestCost)

            val attemptResult: JsonNode? = privateRequestMutex.withLock {
                val credentials = configService.getConfig().kraken
                check(credentials.apiKey.value.isNotBlank()) { KrakenApiConstants.ERROR_API_KEY_NULL }
                val nonce = nonceGenerator.incrementAndGet().toString()
                val payload = data.toMutableMap()
                payload[KrakenApiConstants.PARAM_NONCE] = nonce

                val postData =
                    payload.entries.joinToString("&") {
                        "${URLEncoder.encode(it.key, Charsets.UTF_8)}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
                    }
                val signature = KrakenSigning.sign(
                    path = path,
                    nonce = nonce,
                    postData = postData,
                    base64Secret = credentials.privateKey.value,
                )

                val response =
                    httpClient.post(apiUrl + path) {
                        header(KrakenApiConstants.HEADER_API_KEY, credentials.apiKey.value)
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
                            val bumpAmount = 100_000_000L * (1L shl retryCount)
                            log.warn(
                                "Invalid nonce detected. Adjusting nonce generator by {} and retrying (Attempt {}/{})",
                                bumpAmount,
                                retryCount + 1,
                                maxRetries,
                            )
                            nonceGenerator.addAndGet(bumpAmount)
                            retryCount++
                            null
                        } else if (
                            errorMsg.contains("permission", ignoreCase = true) &&
                            path in setOf(
                                KrakenApiConstants.PATH_DEPOSIT_STATUS,
                                KrakenApiConstants.PATH_WITHDRAW_STATUS,
                            )
                        ) {
                            throw KrakenApiPermissionDeniedException(path, errorMsg)
                        } else {
                            throw RuntimeException("${KrakenApiConstants.ERROR_API_PREFIX}$errorMsg")
                        }
                    } else {
                        root.path(KrakenApiConstants.FIELD_RESULT)
                    }
                } catch (e: JsonProcessingException) {
                    throw RuntimeException(
                        KrakenApiConstants.ERROR_PARSE_PRIVATE,
                        e,
                    )
                }
            }

            if (attemptResult != null) return attemptResult
        }
    }
}
