package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.ExchangeTradePage
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.service.BalanceRead
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.DirectKrakenEvidenceService
import com.gemini.krakenbot.service.DirectTickerRequest
import com.gemini.krakenbot.service.KrakenCredentialsUnavailableException
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.SpendableBalanceService
import com.gemini.krakenbot.service.TickerRead
import com.gemini.krakenbot.util.PrecisionConstants
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class KrakenServiceImpl(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val publicRateLimiter: PublicRateLimiter = PublicRateLimiter(),
    private val clock: () -> Instant = Instant::now,
    private val metadataCacheTtlSeconds: Long = METADATA_CACHE_TTL_SECONDS,
) : KrakenService,
    DirectKrakenEvidenceService,
    SpendableBalanceService {
    private val log = LoggerFactory.getLogger(KrakenServiceImpl::class.java)

    private val nonceGenerator = AtomicLong(System.currentTimeMillis() * 1_000_000L)

    /** Bounds exception text persisted into order error rows / dashboard payloads. */
    private companion object {
        const val MAX_ERROR_MESSAGE_LENGTH = 500
        const val MAX_TRADE_PAGE_LIMIT = 100
        const val METADATA_CACHE_TTL_SECONDS = 900L
    }

    @Volatile
    private var cachedMetadata: Pair<Instant, List<KrakenAssetMetadata>>? = null

    private val transport = KrakenTransport(
        configService = configService,
        objectMapper = objectMapper,
        httpClient = httpClient,
        rateLimiter = rateLimiter,
        nonceGenerator = nonceGenerator,
        publicRateLimiter = publicRateLimiter,
    )

    override suspend fun getApiCallCounter(): Double = rateLimiter.getCurrentCounter()

    override suspend fun getFundingEvidenceScope(): String {
        val credentials = configService.getConfig().kraken
        // Normalize incidental whitespace so a pasted secret with a trailing newline
        // does not fork the scope digest away from the same material without it.
        val material = "${credentials.apiKey.value.trim()}\u0000${credentials.privateKey.value.trim()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    override suspend fun getAuthenticatedAccountIdentity(): String? =
        readApiKeyInfo().path(KrakenApiConstants.FIELD_IBAN).takeIf {
            it.isTextual
        }
            ?.asText()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private suspend fun readApiKeyInfo(): JsonNode = queryPrivate(KrakenApiConstants.PATH_API_KEY_INFO, emptyMap())

    private suspend fun <T> retryWithFlow(
        actionName: String,
        maxAttempts: Int = 5,
        maxLockoutAttempts: Int = 9,
        initialBackoffMs: Long = 2000,
        rateLimitBackoffMs: Long = 10000,
        maxBackoffMs: Long = 60_000,
        maxRateLimitBackoffMs: Long = 60_000,
        initialLockoutBackoffMs: Long = 10_000,
        maxLockoutBackoffMs: Long = 15.minutes.inWholeMilliseconds,
        retryTooManyRequests: Boolean = false,
        block: suspend () -> T,
    ): T = flow {
        var currentBackoff = initialBackoffMs
        var currentRateLimitBackoff = rateLimitBackoffMs
        var currentLockoutBackoff = initialLockoutBackoffMs
        var attempt = 0
        var lockoutAttempt = 0

        while (true) {
            try {
                emit(block())
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = (e as? ResponseException)?.response?.status?.value
                val isRawRateLimit = status == 429
                val isRawLockout = status == 503
                val isRateLimit =
                    isRawRateLimit ||
                        e.message?.contains(KrakenApiConstants.ERROR_RATE_LIMIT_EXCEEDED) == true ||
                        (
                            retryTooManyRequests &&
                                e.message?.contains(KrakenApiConstants.ERROR_TOO_MANY_REQUESTS) == true
                            )
                val isLockout = isRawLockout || e.message?.contains(KrakenApiConstants.ERROR_TEMPORARY_LOCKOUT) == true
                val isRetryableHttp = status == 429 || (status != null && status in 500..504)
                val isNetworkOrTransient = e is IOException
                val retryable = isNetworkOrTransient || isRetryableHttp || isRateLimit || isLockout
                val attemptsUsed = if (isLockout) lockoutAttempt else attempt
                val attemptLimit = if (isLockout) maxLockoutAttempts else maxAttempts

                if (retryable && attemptsUsed < attemptLimit - 1) {
                    val waitTime =
                        when {
                            isLockout -> currentLockoutBackoff.coerceAtMost(maxLockoutBackoffMs)
                            isRateLimit -> currentRateLimitBackoff.coerceAtMost(maxRateLimitBackoffMs)
                            else -> currentBackoff.coerceAtMost(maxBackoffMs)
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
                            currentRateLimitBackoff = (currentRateLimitBackoff * 2).coerceAtMost(maxRateLimitBackoffMs)
                            attempt++
                        }

                        else -> {
                            currentBackoff = (currentBackoff * 2).coerceAtMost(maxBackoffMs)
                            attempt++
                        }
                    }
                } else {
                    throw e
                }
            }
        }
    }.first()

    override suspend fun getBalances(): RawBalances = readBalancesWithCapture().balances

    override suspend fun readBalancesWithCapture(): BalanceRead {
        val path = KrakenApiConstants.PATH_BALANCE
        val requestStartedAt = Instant.now()
        val response = queryPrivate(path, emptyMap())
        val responseEndedAt = Instant.now()
        return BalanceRead(
            balances = KrakenParsers.parseBalances(response),
            capture = KrakenParsers.parseDirectBalanceCapture(response, requestStartedAt, responseEndedAt),
        )
    }

    override suspend fun getSpendableBalances(): RawBalances {
        val response = queryPrivate(KrakenApiConstants.PATH_BALANCE_EX, emptyMap())
        return KrakenParsers.parseSpendableBalances(response)
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices {
        val path = tickerPath(pairs)
        val result = queryPublic(path).path(KrakenApiConstants.FIELD_RESULT)
        return KrakenParsers.parseTickerPrices(result)
    }

    override suspend fun readTickerPricesWithCapture(requests: List<DirectTickerRequest>): TickerRead {
        if (requests.isEmpty()) return TickerRead(emptyMap())

        val pairs = requests.joinToString(",") { it.pair }
        val requestStartedAt = Instant.now()
        val response = queryPublic(tickerPath(pairs))
        val result = response.path(KrakenApiConstants.FIELD_RESULT)
        val responseEndedAt = Instant.now()
        return TickerRead(
            prices = KrakenParsers.parseTickerPrices(result),
            capture = KrakenParsers.parseDirectTickerCapture(
                result,
                requests,
                requestStartedAt,
                responseEndedAt,
            ),
        )
    }

    private fun tickerPath(pairs: String): String =
        "${KrakenApiConstants.PATH_TICKER}?${KrakenApiConstants.PARAM_PAIR}=$pairs"

    override suspend fun getAssetMetadata(): List<KrakenAssetMetadata> = getAssetMetadata(allowStaleFallback = true)

    override suspend fun getFreshAssetMetadata(): List<KrakenAssetMetadata> =
        getAssetMetadata(allowStaleFallback = false)

    private suspend fun getAssetMetadata(allowStaleFallback: Boolean): List<KrakenAssetMetadata> {
        val now = clock()
        val cached = cachedMetadata
        val cacheAge = cached?.let { Duration.between(it.first, now) }
        if (cached != null && metadataCacheTtlSeconds > 0 &&
            cacheAge != null && !cacheAge.isNegative && cacheAge < Duration.ofSeconds(metadataCacheTtlSeconds)
        ) {
            return cached.second
        }
        val fresh = try {
            listOf(
                KrakenApiConstants.ASSET_CLASS_CURRENCY,
                KrakenApiConstants.ASSET_CLASS_TOKENIZED_ASSET,
            ).flatMap { requestedClass ->
                val path = "${KrakenApiConstants.PATH_ASSETS}?${KrakenApiConstants.PARAM_ACLASS}=$requestedClass"
                KrakenParsers.parseAssetMetadata(queryPublic(path))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (allowStaleFallback && cached != null) {
                log.warn("Failed to refresh Kraken asset metadata; falling back to cached metadata.", error)
                return cached.second
            }
            throw error
        }
        cachedMetadata = now to fresh
        return fresh
    }

    fun clearAssetMetadataCache() {
        cachedMetadata = null
    }

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean,
        clOrdId: String?,
    ): OrderResult {
        val normalizedVolume =
            volume
                .setScale(
                    PrecisionConstants.SCALE_CRYPTO,
                    RoundingMode.DOWN,
                ).stripTrailingZeros()

        if (dryRun) {
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
            val message = e.message.orEmpty().ifEmpty { e.javaClass.simpleName }.take(MAX_ERROR_MESSAGE_LENGTH)
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
                cause is ResponseException
        }

    override suspend fun getRecentTradePage(
        startSec: Long?,
        endSec: Long?,
        cursor: String?,
        limit: Int?,
    ): ExchangeTradePage {
        require(limit == null || limit in 1..MAX_TRADE_PAGE_LIMIT) {
            "Trade page limit must be between 1 and $MAX_TRADE_PAGE_LIMIT."
        }
        if (!configService.getConfig().kraken.hasValidCredentials()) {
            throw KrakenCredentialsUnavailableException("Kraken credentials are unavailable for order fill confirmation.")
        }

        val params = mutableMapOf<String, String>()
        if (startSec != null) params[KrakenApiConstants.PARAM_START] = startSec.toString()
        if (endSec != null) params[KrakenApiConstants.PARAM_END] = endSec.toString()
        params[KrakenApiConstants.PARAM_WITH_CURSOR] = "true"
        params[KrakenApiConstants.PARAM_TRADES] = "true"
        params[KrakenApiConstants.PARAM_CONSOLIDATE_TAKER] = "false"
        if (cursor != null) params[KrakenApiConstants.PARAM_CURSOR] = cursor
        if (limit != null) params[KrakenApiConstants.PARAM_LIMIT] = limit.toString()

        val result =
            try {
                queryPrivate(KrakenApiConstants.PATH_TRADES_HISTORY, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to query private TradesHistory endpoint", e)
                throw e
            }

        return KrakenParsers.parseTradePage(result)
    }

    private suspend fun queryPublic(path: String): JsonNode = retryWithFlow(
        actionName = "queryPublic($path)",
        retryTooManyRequests = true,
    ) {
        transport.queryPublic(path)
    }

    private suspend fun queryPrivate(path: String, data: Map<String, String>): JsonNode = retryWithFlow(
        actionName = "queryPrivate($path)",
        maxAttempts = if (path == KrakenApiConstants.PATH_ADD_ORDER) 1 else 5,
        maxLockoutAttempts = if (path == KrakenApiConstants.PATH_ADD_ORDER) 1 else 9,
    ) {
        transport.queryPrivate(path, data)
    }
}
