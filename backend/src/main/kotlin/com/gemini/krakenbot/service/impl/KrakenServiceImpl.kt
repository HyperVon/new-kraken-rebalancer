package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.InternalTransferRecord
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.WithdrawStatusRecord
import com.gemini.krakenbot.service.BoundedTradeHistoryService
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenCredentialsUnavailableException
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RecoveryTradeHistoryService
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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class KrakenServiceImpl(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val publicRateLimiter: PublicRateLimiter = PublicRateLimiter(),
) : KrakenService,
    SpendableBalanceService,
    BoundedTradeHistoryService,
    RecoveryTradeHistoryService {
    private val log = LoggerFactory.getLogger(KrakenServiceImpl::class.java)

    private val nonceGenerator = AtomicLong(System.currentTimeMillis() * 1_000_000L)

    /** Bounds exception text persisted into order error rows / dashboard payloads. */
    private companion object {
        const val MAX_ERROR_MESSAGE_LENGTH = 500
        const val FUNDING_STATUS_PAGE_SIZE = 25
    }

    private val transport = KrakenTransport(
        configService = configService,
        objectMapper = objectMapper,
        httpClient = httpClient,
        rateLimiter = rateLimiter,
        nonceGenerator = nonceGenerator,
        publicRateLimiter = publicRateLimiter,
    )

    private val lastFetchedCount = AtomicInteger(0)
    private val lastLedgerCount = AtomicInteger(0)
    private val lastLedgerRawPageSize = AtomicInteger(0)

    override fun getLastTradeHistoryTotalCount(): Int = lastFetchedCount.get()

    override fun getLastLedgerTotalCount(): Int = lastLedgerCount.get()

    override fun getLastLedgerRawPageSize(): Int = lastLedgerRawPageSize.get()

    override suspend fun getApiCallCounter(): Double = rateLimiter.getCurrentCounter()

    override suspend fun getFundingEvidenceScope(): String {
        val credentials = configService.getConfig().kraken
        // Normalize incidental whitespace so a pasted secret with a trailing newline
        // does not fork the scope digest away from the same material without it.
        val material = "${credentials.apiKey.value.trim()}\u0000${credentials.privateKey.value.trim()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

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

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> =
        getTradeHistoryUntil(startSec, offset, null)

    override suspend fun getTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord> {
        lastFetchedCount.set(0)
        if (!configService.getConfig().kraken.hasValidCredentials()) {
            throw KrakenCredentialsUnavailableException("Kraken credentials are unavailable for trade history.")
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

    override suspend fun getRecoveryTradeHistoryUntil(
        startSec: Long?,
        offset: Int?,
        endSec: Long?,
    ): List<TradeRecord> {
        lastFetchedCount.set(0)
        if (!configService.getConfig().kraken.hasValidCredentials()) {
            throw KrakenCredentialsUnavailableException("Kraken credentials are unavailable for trade history.")
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
                log.error("Failed to query private TradesHistory endpoint for recovery", e)
                throw e
            }

        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        val (trades, count) = KrakenParsers.parseTradeHistory(result, allocations, preserveUnmapped = true)
        lastFetchedCount.set(count)
        return trades
    }

    override suspend fun getLedgers(
        startSec: Long?,
        offset: Int?,
        endSec: Long?,
        types: Set<String>?,
    ): List<LedgerEvent> {
        lastLedgerCount.set(0)
        if (!configService.getConfig().kraken.hasValidCredentials()) {
            throw KrakenCredentialsUnavailableException("Kraken credentials are unavailable for ledgers.")
        }

        val params = mutableMapOf<String, String>()
        if (startSec != null) params[KrakenApiConstants.PARAM_START] = startSec.toString()
        if (endSec != null) params[KrakenApiConstants.PARAM_END] = endSec.toString()
        if (offset != null) params[KrakenApiConstants.PARAM_OFS] = offset.toString()

        if (types != null && types.isEmpty()) return emptyList()

        val queryTypes = types?.sorted()?.groupBy(::ledgerQueryType)
        if (queryTypes != null && queryTypes.size > 1) {
            val fanOutParams = params - KrakenApiConstants.PARAM_OFS
            val pages = queryTypes.map { (queryType, responseTypes) ->
                queryLedgerPage(
                    fanOutParams + (KrakenApiConstants.PARAM_TYPE to queryType),
                    responseTypes.toSet(),
                )
            }
            lastLedgerCount.set(pages.sumOf { it.totalCount })
            lastLedgerRawPageSize.set(pages.sumOf { it.rawPageSize })
            return pages.flatMap { it.entries }
        }

        val pageParams = if (queryTypes != null) {
            params + (KrakenApiConstants.PARAM_TYPE to queryTypes.keys.single())
        } else {
            params
        }
        val pageResult = queryLedgerPage(pageParams, types)
        lastLedgerCount.set(pageResult.totalCount)
        lastLedgerRawPageSize.set(pageResult.rawPageSize)
        return pageResult.entries
    }

    // TODO: Funding provenance currently uses legacy DepositStatus/WithdrawStatus APIs.
    // Migrate to List Funding Deposits / List Funding Withdrawals in a follow-up.
    override suspend fun getDepositStatus(startSec: Long?, endSec: Long?): List<DepositStatusRecord> = getFundingStatus(
        path = KrakenApiConstants.PATH_DEPOSIT_STATUS,
        startSec = startSec,
        endSec = endSec,
        parser = KrakenParsers::parseDepositStatusPage,
    )

    override suspend fun getWithdrawStatus(startSec: Long?, endSec: Long?): List<WithdrawStatusRecord> =
        getFundingStatus(
            path = KrakenApiConstants.PATH_WITHDRAW_STATUS,
            startSec = startSec,
            endSec = endSec,
            parser = KrakenParsers::parseWithdrawStatusPage,
        )

    /** Spot REST has no historical Futures-transfer query to call here. */
    override suspend fun getInternalTransfers(startSec: Long?, endSec: Long?): List<InternalTransferRecord> =
        emptyList()

    private suspend fun <T> getFundingStatus(
        path: String,
        startSec: Long?,
        endSec: Long?,
        parser: (JsonNode) -> FundingStatusPage<T>,
    ): List<T> {
        if (!configService.getConfig().kraken.hasValidCredentials()) {
            throw KrakenCredentialsUnavailableException("Kraken credentials are unavailable for funding status.")
        }

        val baseParams = mutableMapOf<String, String>()
        if (startSec != null) baseParams[KrakenApiConstants.PARAM_START] = startSec.toString()
        if (endSec != null) baseParams[KrakenApiConstants.PARAM_END] = endSec.toString()
        baseParams[KrakenApiConstants.PARAM_CURSOR] = "true"
        baseParams[KrakenApiConstants.PARAM_LIMIT] = FUNDING_STATUS_PAGE_SIZE.toString()

        val records = mutableListOf<T>()
        val seenCursors = mutableSetOf("true")
        var cursor: String? = null
        do {
            val params = baseParams.toMutableMap()
            if (cursor != null) params[KrakenApiConstants.PARAM_CURSOR] = cursor
            val result = try {
                queryPrivate(path, params)
            } catch (e: KrakenApiPermissionDeniedException) {
                log.error(
                    "Kraken denied funding-status endpoint {}. DepositStatus requires Funds: Query; " +
                        "WithdrawStatus requires Funds: Withdraw or Data: Query ledger entries.",
                    path,
                    e,
                )
                throw e
            }
            val page = parser(result)
            records += page.records
            val nextCursor = page.nextCursor?.trim()?.takeIf(String::isNotEmpty)
            if (nextCursor == null) {
                cursor = null
            } else if (!seenCursors.add(nextCursor)) {
                throw IllegalStateException("Kraken funding status pagination repeated cursor for $path")
            } else {
                cursor = nextCursor
            }
        } while (cursor != null)
        return records
    }

    private fun ledgerQueryType(type: String): String = when (type) {
        KrakenApiConstants.LEDGER_TYPE_SPEND,
        KrakenApiConstants.LEDGER_TYPE_RECEIVE,
        -> KrakenApiConstants.LEDGER_TYPE_SALE

        KrakenApiConstants.LEDGER_TYPE_EARN -> KrakenApiConstants.LEDGER_TYPE_ALL

        else -> type
    }

    private suspend fun queryLedgerPage(
        params: Map<String, String>,
        expectedTypes: Set<String>?,
    ): KrakenParsers.LedgerPageResult {
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
                throw e
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
