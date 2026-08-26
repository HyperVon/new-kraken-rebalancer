package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.BoundedTradeHistoryService
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.SpendableBalanceService
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class KrakenServiceImpl(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter = RateLimiter(),
) : KrakenService,
    SpendableBalanceService,
    BoundedTradeHistoryService {
    private val log = LoggerFactory.getLogger(KrakenServiceImpl::class.java)

    private val nonceGenerator = AtomicLong(System.currentTimeMillis() * 1_000_000L)

    /** Bounds exception text persisted into order error rows / dashboard payloads. */
    private companion object {
        const val MAX_ERROR_MESSAGE_LENGTH = 500
    }

    private val transport = KrakenTransport(
        configService = configService,
        objectMapper = objectMapper,
        httpClient = httpClient,
        rateLimiter = rateLimiter,
        nonceGenerator = nonceGenerator,
    )

    private val lastFetchedCount = AtomicInteger(0)
    private val lastLedgerCount = AtomicInteger(0)

    override fun getLastTradeHistoryTotalCount(): Int = lastFetchedCount.get()

    override fun getLastLedgerTotalCount(): Int = lastLedgerCount.get()

    override suspend fun getApiCallCounter(): Double = rateLimiter.getCurrentCounter()

    private suspend fun <T> retryWithFlow(
        actionName: String,
        maxAttempts: Int = 5,
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
                    isRawRateLimit || e.message?.contains(KrakenApiConstants.ERROR_RATE_LIMIT_EXCEEDED) == true
                val isLockout = isRawLockout || e.message?.contains(KrakenApiConstants.ERROR_TEMPORARY_LOCKOUT) == true
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
        return KrakenParsers.parseBalances(response)
    }

    override suspend fun getSpendableBalances(): RawBalances {
        val response = queryPrivate(KrakenApiConstants.PATH_BALANCE_EX, emptyMap())
        return KrakenParsers.parseSpendableBalances(response)
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices {
        val path = "${KrakenApiConstants.PATH_TICKER}?${KrakenApiConstants.PARAM_PAIR}=$pairs"
        val result = queryPublic(path).path(KrakenApiConstants.FIELD_RESULT)
        return KrakenParsers.parseTickerPrices(result)
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
                    RoundingMode.DOWN,
                ).stripTrailingZeros()

        val isDryRun = dryRun ?: configService.getConfig().settings.dryRun.also {
            log.warn("executeOrder called without a dryRun argument; resolved from live config: {}", it)
        }
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

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> =
        getTradeHistoryUntil(startSec, offset, null)

    override suspend fun getTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord> {
        if (!configService.getConfig().kraken.hasValidCredentials()) {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history fetch.")
            return emptyList()
        }

        val params = mutableMapOf<String, String>()
        if (startSec != null) params[KrakenApiConstants.PARAM_START] = startSec.toString()
        if (endSec != null) params[KrakenApiConstants.PARAM_END] = endSec.toString()
        if (offset != null) params[KrakenApiConstants.PARAM_OFS] = offset.toString()

        val result =
            try {
                queryPrivate(KrakenApiConstants.PATH_TRADES_HISTORY, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to query private TradesHistory endpoint", e)
                throw e
            }

        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        val (trades, count) = KrakenParsers.parseTradeHistory(result, allocations)
        lastFetchedCount.set(count)
        return trades
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
        if (startSec != null) params[KrakenApiConstants.PARAM_START] = startSec.toString()
        if (endSec != null) params[KrakenApiConstants.PARAM_END] = endSec.toString()
        if (offset != null) params[KrakenApiConstants.PARAM_OFS] = offset.toString()

        val sortedTypes = types?.sorted()
        if (sortedTypes != null && sortedTypes.size > 1) {
            val fanOutParams = params - KrakenApiConstants.PARAM_OFS
            val pages = sortedTypes.map { type ->
                queryLedgerPage(fanOutParams + (KrakenApiConstants.PARAM_TYPE to type), types)
            }
            lastLedgerCount.set(pages.sumOf { it.second })
            return pages.flatMap { it.first }
        }
        val pageParams = if (sortedTypes != null) {
            params + (KrakenApiConstants.PARAM_TYPE to sortedTypes.single())
        } else {
            params
        }
        val (entries, count) = queryLedgerPage(pageParams, types)
        lastLedgerCount.set(count)
        return entries
    }

    private suspend fun queryLedgerPage(
        params: Map<String, String>,
        expectedTypes: Set<String>?,
    ): Pair<List<LedgerEvent>, Int> {
        val result =
            try {
                queryPrivate(KrakenApiConstants.PATH_LEDGERS, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to query private Ledgers endpoint", e)
                throw e
            }

        return KrakenParsers.parseLedgerPage(result, expectedTypes)
    }

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> {
        val params = mutableMapOf<String, String>()
        params[KrakenApiConstants.PARAM_PAIR] = pair
        params[KrakenApiConstants.PARAM_INTERVAL] = interval.toString()
        if (since != null) params[KrakenApiConstants.PARAM_SINCE] = since.toString()
        val queryStr = params.map { "${it.key}=${it.value}" }.joinToString("&")
        val path = "${KrakenApiConstants.PATH_OHLC}?$queryStr"
        val root =
            try {
                queryPublic(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to query public OHLC endpoint for pair $pair", e)
                return emptyList()
            }

        return KrakenParsers.parseOHLC(root, pair)
    }

    private suspend fun queryPublic(path: String): JsonNode = retryWithFlow("queryPublic($path)") {
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
