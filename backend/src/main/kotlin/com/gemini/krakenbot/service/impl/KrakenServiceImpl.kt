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
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
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
        const val FUNDING_V1_PAGE_SIZE = 500
        const val LEGACY_FUNDING_ENRICHMENT_LIMIT = 500
        const val MAX_FUNDING_PAGES = 20
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
    private val lastTradeHistoryCountPresent = AtomicBoolean(false)
    private val lastTradeHistoryPageShapeValid = AtomicBoolean(false)
    private val lastTradeHistoryRawPageSize = AtomicInteger(0)
    private val lastLedgerCount = AtomicInteger(0)
    private val lastLedgerCountPresent = AtomicBoolean(false)
    private val lastLedgerPageShapeValid = AtomicBoolean(false)
    private val lastLedgerRawPageSize = AtomicInteger(0)

    override fun getLastTradeHistoryTotalCount(): Int = lastFetchedCount.get()

    override fun hasLastTradeHistoryTotalCount(): Boolean = lastTradeHistoryCountPresent.get()

    override fun hasLastTradeHistoryPageShape(): Boolean = lastTradeHistoryPageShapeValid.get()

    override fun getLastTradeHistoryRawPageSize(): Int = lastTradeHistoryRawPageSize.get()

    override fun getLastLedgerTotalCount(): Int = lastLedgerCount.get()

    override fun hasLastLedgerTotalCount(): Boolean = lastLedgerCountPresent.get()

    override fun hasLastLedgerPageShape(): Boolean = lastLedgerPageShapeValid.get()

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
        lastTradeHistoryCountPresent.set(false)
        lastTradeHistoryPageShapeValid.set(false)
        lastTradeHistoryRawPageSize.set(0)
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
        val page = KrakenParsers.parseTradeHistoryPage(result, allocations)
        lastFetchedCount.set(page.totalCount)
        lastTradeHistoryCountPresent.set(page.hasTotalCount)
        lastTradeHistoryPageShapeValid.set(page.hasTradeContainer)
        lastTradeHistoryRawPageSize.set(page.rawPageSize)
        return page.entries
    }

    override suspend fun getRecoveryTradeHistoryUntil(
        startSec: Long?,
        offset: Int?,
        endSec: Long?,
    ): List<TradeRecord> {
        lastFetchedCount.set(0)
        lastTradeHistoryCountPresent.set(false)
        lastTradeHistoryPageShapeValid.set(false)
        lastTradeHistoryRawPageSize.set(0)
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
        val page = KrakenParsers.parseTradeHistoryPage(result, allocations, preserveUnmapped = true)
        lastFetchedCount.set(page.totalCount)
        lastTradeHistoryCountPresent.set(page.hasTotalCount)
        lastTradeHistoryPageShapeValid.set(page.hasTradeContainer)
        lastTradeHistoryRawPageSize.set(page.rawPageSize)
        return page.entries
    }

    override suspend fun getLedgers(
        startSec: Long?,
        offset: Int?,
        endSec: Long?,
        types: Set<String>?,
    ): List<LedgerEvent> {
        lastLedgerCount.set(0)
        lastLedgerCountPresent.set(false)
        lastLedgerPageShapeValid.set(false)
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
            lastLedgerCountPresent.set(pages.all { it.hasTotalCount })
            lastLedgerPageShapeValid.set(pages.all { it.hasLedgerContainer })
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
        lastLedgerCountPresent.set(pageResult.hasTotalCount)
        lastLedgerPageShapeValid.set(pageResult.hasLedgerContainer)
        lastLedgerRawPageSize.set(pageResult.rawPageSize)
        return pageResult.entries
    }

    override suspend fun getDepositStatus(startSec: Long?, endSec: Long?): List<DepositStatusRecord> {
        val records = fetchV1FundingRecords(
            path = KrakenApiConstants.PATH_FUNDING_DEPOSITS,
            containerField = KrakenApiConstants.FIELD_DEPOSITS,
            idField = KrakenApiConstants.FIELD_DEPOSIT_ID,
            startSec = startSec,
            endSec = endSec,
        )
        val metadata = fetchLegacyFundingMetadata(
            path = KrakenApiConstants.PATH_DEPOSIT_STATUS,
            startSec = startSec,
            endSec = endSec,
            parser = KrakenParsers::parseDepositStatusPage,
            refid = { it.refid },
            metadata = { FundingMetadata(method = it.method, txid = it.txid) },
        )
        var methodNames: Map<String, String>? = null
        return records.map { record ->
            val legacy = metadata[record.id]
            val method = legacy?.method ?: record.methodId?.let { methodId ->
                val names = methodNames
                    ?: fetchFundingMethodNames(KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT)
                        .also { methodNames = it }
                names[methodId]
            }
            DepositStatusRecord(
                refid = record.id,
                txid = legacy?.txid,
                asset = record.asset,
                amount = record.amount,
                fee = record.fee,
                time = record.time,
                status = record.status,
                method = method,
                hasAuthoritativeFee = record.hasAuthoritativeFee,
            )
        }
    }

    override suspend fun getWithdrawStatus(startSec: Long?, endSec: Long?): List<WithdrawStatusRecord> {
        val records = fetchV1FundingRecords(
            path = KrakenApiConstants.PATH_FUNDING_WITHDRAWALS,
            containerField = KrakenApiConstants.FIELD_WITHDRAWALS,
            idField = KrakenApiConstants.FIELD_WITHDRAWAL_ID,
            startSec = startSec,
            endSec = endSec,
        )
        val metadata = fetchLegacyFundingMetadata(
            path = KrakenApiConstants.PATH_WITHDRAW_STATUS,
            startSec = startSec,
            endSec = endSec,
            parser = KrakenParsers::parseWithdrawStatusPage,
            refid = { it.refid },
            metadata = { FundingMetadata(method = it.method, txid = it.txid) },
        )
        var methodNames: Map<String, String>? = null
        return records.map { record ->
            val legacy = metadata[record.id]
            val method = legacy?.method ?: record.methodId?.let { methodId ->
                val names = methodNames
                    ?: fetchFundingMethodNames(KrakenApiConstants.PATH_FUNDING_METHODS_WITHDRAW)
                        .also { methodNames = it }
                names[methodId]
            }
            WithdrawStatusRecord(
                refid = record.id,
                txid = legacy?.txid,
                asset = record.asset,
                amount = record.amount,
                fee = record.fee,
                time = record.time,
                status = record.status,
                method = method,
                hasAuthoritativeFee = record.hasAuthoritativeFee,
            )
        }
    }

    /** Spot REST has no historical Futures-transfer query to call here. */
    override suspend fun getInternalTransfers(startSec: Long?, endSec: Long?): List<InternalTransferRecord> =
        emptyList()

    private data class FundingMetadata(val method: String? = null, val txid: String? = null)

    private suspend fun fetchV1FundingRecords(
        path: String,
        containerField: String,
        idField: String,
        startSec: Long?,
        endSec: Long?,
    ): List<FundingV1Record> {
        if (!configService.getConfig().kraken.hasValidCredentials()) {
            throw KrakenCredentialsUnavailableException("Kraken credentials are unavailable for funding history.")
        }

        val firstPageParams = mutableMapOf<String, String>()
        startSec?.let { firstPageParams[KrakenApiConstants.PARAM_START_TIME] = Instant.ofEpochSecond(it).toString() }
        endSec?.let { firstPageParams[KrakenApiConstants.PARAM_END_TIME] = Instant.ofEpochSecond(it).toString() }
        firstPageParams[KrakenApiConstants.PARAM_LIMIT] = FUNDING_V1_PAGE_SIZE.toString()

        val records = mutableListOf<FundingV1Record>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pageIndex = 0
        while (true) {
            val params = cursor?.let { mapOf(KrakenApiConstants.PARAM_CURSOR to it) } ?: firstPageParams
            val result = try {
                queryPrivateGet(path, params)
            } catch (e: KrakenApiPermissionDeniedException) {
                log.error("Kraken denied funding-history endpoint {}. Funds: Query permission is required.", path, e)
                throw e
            }
            val page = KrakenParsers.parseFundingV1Page(result, containerField, idField)
            if (page.rawCount != page.records.size) {
                throw IllegalStateException(
                    "Kraken $path returned funding records that could not be parsed; funding history is incomplete.",
                )
            }
            records += page.records
            val nextCursor = page.nextCursor?.trim()?.takeIf(String::isNotEmpty) ?: break
            if (!seenCursors.add(nextCursor)) {
                throw IllegalStateException("Kraken funding history pagination repeated cursor for $path.")
            }
            cursor = nextCursor
            pageIndex++
            if (pageIndex >= MAX_FUNDING_PAGES) {
                throw IllegalStateException(
                    "Kraken funding history pagination exceeded $MAX_FUNDING_PAGES pages for $path.",
                )
            }
        }
        return records
    }

    private suspend fun <T> fetchLegacyFundingMetadata(
        path: String,
        startSec: Long?,
        endSec: Long?,
        parser: (JsonNode) -> FundingStatusPage<T>,
        refid: (T) -> String,
        metadata: (T) -> FundingMetadata,
    ): Map<String, FundingMetadata> {
        return try {
            val params = mutableMapOf<String, String>()
            startSec?.let { params[KrakenApiConstants.PARAM_START] = it.toString() }
            endSec?.let { params[KrakenApiConstants.PARAM_END] = it.toString() }
            params[KrakenApiConstants.PARAM_LIMIT] = LEGACY_FUNDING_ENRICHMENT_LIMIT.toString()
            val page = parser(queryPrivate(path, params))
            if (page.rawCount >= LEGACY_FUNDING_ENRICHMENT_LIMIT) {
                log.warn(
                    "Legacy funding metadata at {} reached the {} record limit; metadata is incomplete.",
                    path,
                    LEGACY_FUNDING_ENRICHMENT_LIMIT,
                )
                return emptyMap()
            }
            if (page.rawCount != page.records.size) {
                log.warn(
                    "Legacy funding metadata at {} dropped {} unparseable entries; metadata is incomplete.",
                    path,
                    page.rawCount - page.records.size,
                )
                return emptyMap()
            }
            page.records.associate { refid(it).trim() to metadata(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Legacy funding metadata fetch failed for {}; funding records may lack external proof.", path, e)
            emptyMap()
        }
    }

    private suspend fun fetchFundingMethodNames(path: String): Map<String, String> {
        return try {
            val names = mutableMapOf<String, String>()
            val seenCursors = mutableSetOf<String>()
            var cursor: String? = null
            var pageIndex = 0
            while (pageIndex < MAX_FUNDING_PAGES) {
                val params = cursor?.let { mapOf(KrakenApiConstants.PARAM_CURSOR to it) }
                    ?: mapOf(KrakenApiConstants.PARAM_LIMIT to FUNDING_V1_PAGE_SIZE.toString())
                val page = KrakenParsers.parseFundingMethodsPage(queryPrivateGet(path, params))
                if (page.rawCount != page.records.size) {
                    log.warn("Funding method list {} returned unparseable entries; names may be incomplete.", path)
                }
                page.records.forEach { names.putIfAbsent(it.methodId, it.methodName) }
                val nextCursor = page.nextCursor?.trim()?.takeIf(String::isNotEmpty) ?: return names
                if (!seenCursors.add(nextCursor)) return names
                cursor = nextCursor
                pageIndex++
            }
            names
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Funding method list fetch failed for {}; funding records may lack external proof.", path, e)
            emptyMap()
        }
    }

    private suspend fun queryPrivateGet(path: String, query: Map<String, String>): JsonNode = retryWithFlow(
        actionName = "queryPrivateGet($path)",
        maxAttempts = 5,
        maxLockoutAttempts = 9,
    ) {
        transport.queryPrivateGet(path, query)
    }

    private fun ledgerQueryType(type: String): String = when (type) {
        KrakenApiConstants.LEDGER_TYPE_SPEND,
        KrakenApiConstants.LEDGER_TYPE_RECEIVE,
        -> KrakenApiConstants.LEDGER_TYPE_SALE

        KrakenApiConstants.LEDGER_TYPE_EARN,
        KrakenApiConstants.LEDGER_TYPE_REWARD,
        KrakenApiConstants.LEDGER_TYPE_CONVERSION,
        -> KrakenApiConstants.LEDGER_TYPE_ALL

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
