package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import java.math.BigDecimal
import java.time.Instant

const val HISTORY_PAGE_LIMIT = 500
const val HISTORY_MAX_PAGE_LIMIT = 2_000
const val HISTORY_MAX_CHART_POINTS = 2_000
const val HISTORY_WALLET_SCOPE = "KRAKEN_DEFAULT_WALLET"
const val HISTORY_BALANCE_SOURCE = "KRAKEN_PRIVATE_BALANCE"
const val HISTORY_PRICE_SOURCE = "KRAKEN_PUBLIC_TICKER_LAST_TRADE_C0"

enum class HistorySourceMode { ACCOUNT, SIMULATION }

/** Identity and managed-asset scope for directly observed cycle samples. */
data class HistorySourceScope(
    val mode: HistorySourceMode,
    val managedScopeFingerprint: String,
    val managedScopeSymbols: List<String>,
    val accountIdentityDigest: String? = null,
    val simulationSessionId: String? = null,
) {
    init {
        require(managedScopeFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "History managed scope fingerprint must be a SHA-256 digest."
        }
        require(managedScopeSymbols.isNotEmpty()) { "History observation scope cannot be empty." }
        when (mode) {
            HistorySourceMode.ACCOUNT -> {
                require(accountIdentityDigest?.matches(Regex("[0-9a-f]{64}")) == true) {
                    "Account history requires a verified account identity digest."
                }
                require(simulationSessionId == null) { "Account history cannot have a simulation session." }
            }

            HistorySourceMode.SIMULATION -> {
                require(!simulationSessionId.isNullOrBlank()) { "Simulation history requires a session id." }
                require(accountIdentityDigest == null) { "Simulation history cannot have an account identity." }
            }
        }
    }

    val sourceKey: String
        get() = buildString {
            append(mode.name)
            append(':')
            append(accountIdentityDigest ?: simulationSessionId)
            append(':')
            append(managedScopeFingerprint)
        }
}

enum class HistoryObservationStatus { COMPLETE, INCOMPLETE }

enum class HistorySampleSelection {
    FINAL,
    PRE_TRADE,
    POST_TRADE,
    POST_TRADE_REFRESH_FAILED,
}

enum class HistoryAssetValueStatus {
    COMPLETE,
    MISSING_BALANCE,
    INVALID_BALANCE,
    AMBIGUOUS_BALANCE,
    MISSING_PRICE,
    INVALID_PRICE,
    INVALID_TIME_WINDOW,
    SIMULATED,
    UNAVAILABLE_CYCLE_EVIDENCE,
}

data class HistoryAssetValue(
    val symbol: String,
    val status: HistoryAssetValueStatus,
    val rawBalanceKeys: List<String> = emptyList(),
    val rawBalanceValues: Map<String, String?> = emptyMap(),
    val quantity: BigDecimal?,
    val requestedPair: String? = null,
    val responsePair: String? = null,
    val candidateResponsePairs: List<String> = emptyList(),
    val candidateRawPrices: Map<String, String?> = emptyMap(),
    val rawPrice: String? = null,
    val priceUsd: BigDecimal? = null,
    val valueUsd: BigDecimal? = null,
    val reason: String? = null,
)

data class HistoryObservation(
    val observationId: String,
    val cycleId: String,
    val sourceScope: HistorySourceScope,
    val walletScope: String = HISTORY_WALLET_SCOPE,
    val balanceSource: String = HISTORY_BALANCE_SOURCE,
    val priceSource: String = HISTORY_PRICE_SOURCE,
    val valuationCurrency: String = "USD",
    val sampleSelection: HistorySampleSelection = HistorySampleSelection.FINAL,
    val observedAt: Instant,
    val balanceRequestStartedAt: Instant,
    val balanceResponseEndedAt: Instant,
    val priceRequestStartedAt: Instant,
    val priceResponseEndedAt: Instant,
    val persistedAt: Instant? = null,
    val status: HistoryObservationStatus,
    val totalUsd: BigDecimal?,
    val incompleteReasons: List<String>,
    val assets: List<HistoryAssetValue>,
) {
    init {
        require(observationId.isNotBlank())
        require(cycleId.isNotBlank())
        require(assets.map { it.symbol }.distinct().size == assets.size)
        require((status == HistoryObservationStatus.COMPLETE) == (totalUsd != null)) {
            "Only complete observations may expose a total value."
        }
    }
}

/**
 * Structured cycle outcome for the activity feed. Legacy rows recorded before outcomes
 * existed carry null and display as [HistoryCycleOutcome.RECORDED]; a historical fill or
 * submission status is never invented to fill the display format.
 */
enum class HistoryCycleOutcome { NO_TRADE, DRY_RUN_PLAN, ORDERS_ATTEMPTED, PROBLEM, RECORDED }

data class HistoryCycleActivity(
    val cycleId: String,
    val sourceScope: HistorySourceScope,
    val occurredAt: Instant,
    val dryRun: Boolean,
    val actions: List<String>,
    val outcome: HistoryCycleOutcome? = null,
)

/** Cursor for continuing a descending time/id history query. */
data class HistorySortCursor(val time: Instant?, val id: String)

/** Activity query in descending occurrence/cycle-id order with optional offset or keyset paging. */
data class HistoryActivityQuery(
    val sourceScope: HistorySourceScope,
    val from: Instant? = null,
    val until: Instant? = null,
    val limit: Int = HISTORY_PAGE_LIMIT,
    val offset: Int = 0,
    val sortCursor: HistorySortCursor? = null,
) {
    init {
        require(from == null || until == null || from <= until) { "History range start must not follow its end." }
        require(limit in 1..HISTORY_MAX_PAGE_LIMIT)
        require(offset >= 0)
    }
}

/** One stored asset-set scope with its recorded value coverage for the scope selector. */
data class HistoryRecordedScope(
    val sourceKey: String,
    val mode: HistorySourceMode,
    val managedScopeFingerprint: String,
    val managedScopeSymbols: List<String>,
    val accountIdentityDigest: String?,
    val simulationSessionId: String?,
    val firstObservedAt: Instant?,
    val lastObservedAt: Instant?,
    val observationCount: Long,
)

/** Honest value-record coverage for one scope; trades/ledgers coverage is reported separately. */
data class HistoryValuesCoverage(
    val sourceKey: String,
    val firstRecordedAt: Instant?,
    val lastRecordedAt: Instant?,
    val completeCount: Long,
    val incompleteCount: Long,
)

data class HistoryRangeQuery(
    /** Inclusive lower time bound. */
    val from: Instant? = null,
    /** Exclusive upper time bound. */
    val until: Instant? = null,
    val sourceScope: HistorySourceScope? = null,
    val limit: Int = HISTORY_PAGE_LIMIT,
    val offset: Int = 0,
) {
    init {
        require(from == null || until == null || from <= until) { "History range start must not follow its end." }
        require(limit in 1..HISTORY_MAX_PAGE_LIMIT)
        require(offset >= 0)
    }
}

data class HistoryEventQuery(
    /** Inclusive lower time bound. */
    val from: Instant? = null,
    /** Exclusive upper time bound. */
    val until: Instant? = null,
    val accountIdentityDigest: String? = null,
    val assetKey: String? = null,
    val pair: String? = null,
    val type: String? = null,
    val limit: Int = HISTORY_PAGE_LIMIT,
    val offset: Int = 0,
    val sortCursor: HistorySortCursor? = null,
) {
    init {
        require(from == null || until == null || from <= until) { "History range start must not follow its end." }
        require(limit in 1..HISTORY_MAX_PAGE_LIMIT)
        require(offset >= 0)
        require(accountIdentityDigest == null || accountIdentityDigest.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Distinct choices visible to the current account and selected time range in the History filters. */
data class HistoryEventFilterOptions(
    val assets: List<String> = emptyList(),
    val pairs: List<String> = emptyList(),
    val types: List<String> = emptyList(),
)

data class HistoryPage<out T>(
    val items: List<T>,
    val total: Long?,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
)

data class HistoryTrade(
    val accountIdentityDigest: String,
    val tradeId: String,
    val orderTxid: String?,
    val postTxid: String?,
    val pair: String?,
    val baseAsset: String?,
    val quoteCurrency: String?,
    /** Kraken does not report a fee currency; spot trade fees settle in the quote currency. */
    val feeCurrency: String? = null,
    val time: Instant?,
    val rawTime: String?,
    val type: String?,
    val orderType: String?,
    val price: String?,
    val cost: String?,
    val fee: String?,
    val volume: String?,
    val margin: String?,
    val misc: String?,
    val tradeSequenceId: String?,
    val maker: Boolean?,
    val ledgerIds: List<String>,
    val rawRecordJson: String?,
    val hasValidTime: Boolean,
    val hasValidPrice: Boolean,
    val hasValidCost: Boolean,
    val hasValidFee: Boolean,
    val hasValidVolume: Boolean,
    val isValidRecord: Boolean,
)

data class HistoryLedger(
    val accountIdentityDigest: String,
    val ledgerId: String,
    val refid: String?,
    val time: Instant?,
    val rawTime: String?,
    val type: String?,
    val subtype: String?,
    val assetClass: String?,
    val asset: String?,
    val amount: String?,
    val fee: String?,
    val balance: String?,
    val rawRecordJson: String?,
    val hasValidTime: Boolean,
    val hasValidAmount: Boolean,
    val hasValidFee: Boolean,
    val hasValidBalance: Boolean,
    val isValidRecord: Boolean,
) {
    /** Kraken's ledger fee is denominated in the same raw asset field. */
    val feeCurrency: String?
        get() = asset
}

enum class HistoryBalanceStatus { VALID, INVALID, MISSING }

class HistoryBalanceCheckpoint(
    val accountIdentityDigest: String,
    val ledgerId: String,
    val observedAt: Instant?,
    val rawAssetClass: String?,
    val rawAssetKey: String?,
    val rawAmount: String?,
    val rawFee: String?,
    val rawBalance: String?,
    val quantity: BigDecimal?,
    val status: HistoryBalanceStatus,
    val reason: String? = null,
)

enum class HistoryDailyValueStatus { COMPLETE, INCOMPLETE, NO_BALANCE, NO_PRICE, AMBIGUOUS_BALANCE, OUTSIDE_COVERAGE }

data class HistoryDailyAssetValue(
    val dayEnd: Instant,
    val accountIdentityDigest: String,
    val rawAssetClass: String?,
    val rawAssetKey: String,
    val quantity: BigDecimal?,
    val priceUsd: BigDecimal?,
    val valueUsd: BigDecimal?,
    val pricePair: String?,
    val priceCandleOpen: Instant?,
    val priceSource: String?,
    val status: HistoryDailyValueStatus,
    val reason: String? = null,
)

data class HistoryDailyPrice(
    val accountIdentityDigest: String,
    val rawAssetKey: String,
    val pair: String,
    val candleOpen: Instant,
    val candleEnd: Instant,
    val closeUsd: BigDecimal,
    val source: String = "KRAKEN_OHLC_DAILY_CLOSE",
)

data class HistoryDailyValue(
    val dayEnd: Instant,
    val accountIdentityDigest: String,
    val sourceAssetScope: String,
    val totalUsd: BigDecimal?,
    val status: HistoryDailyValueStatus,
    val source: String = "KRAKEN_LEDGER_CHECKPOINTS_PLUS_DAILY_OHLC_CLOSE",
    val assets: List<HistoryDailyAssetValue>,
)

enum class HistoryFeed { TRADES, LEDGERS }

/** Canonical sync-stop reasons shared by the synchronizer, coordinator, and status presenter. */
object HistorySyncErrors {
    const val ACCOUNT_SCOPE_CHANGED = "History sync stopped because authenticated account or credentials changed."
    const val API_KEY_RESTRICTIONS_UNAVAILABLE =
        "History sync stopped because Kraken API-key time restrictions could not be verified."
    const val API_KEY_WINDOW_NO_OVERLAP =
        "History sync window does not overlap this Kraken API key's allowed query dates."
    const val API_KEY_WINDOW_PARTIAL =
        "This Kraken API key's allowed query dates cover only part of the remaining history."
    const val API_KEY_RESTRICTIONS_CHANGED =
        "History sync stopped because Kraken API-key query restrictions changed."
    const val PAGE_RESTARTED = "History page was retried from the start of its bounded time window."
    const val PAGE_FAILED = "History page could not be validated after one bounded restart."
}

enum class HistorySyncState { NOT_STARTED, RUNNING, COMPLETE, PAUSED, FAILED }

/**
 * The authenticated account could not be established for a scan. This is an account-level
 * condition: every feed is unavailable until the account can be verified again.
 */
class HistoryAccountUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Job identity: recent refresh, older backfill, and the daily late-record repair stay independent. */
enum class HistorySyncPurpose { RECENT, BACKFILL, REPAIR }

enum class HistorySyncJobState { PENDING, RUNNING, COMPLETE, FAILED, BLOCKED }

/**
 * Durable coordinator job state. The committed [cursor] and frozen window survive a recent
 * scan and a process restart; [completedThroughSec] advances only after every page in the
 * frozen window commits.
 */
data class HistorySyncJob(
    val accountIdentityDigest: String,
    val feed: HistoryFeed,
    val purpose: HistorySyncPurpose,
    val state: HistorySyncJobState,
    val windowStartSec: Long?,
    val windowEndSec: Long?,
    val cursor: String?,
    val completedThroughSec: Long?,
    val lastError: String?,
    val nextRetryAt: Instant?,
    val lastSuccessAt: Instant?,
    val updatedAt: Instant,
)

/** Account/feed view of recent coverage and older import progress for Data status. */
data class HistorySyncProgressSummary(
    val accountIdentityDigest: String,
    val feed: HistoryFeed,
    val recentWindowStartSec: Long?,
    val recentCompletedThroughSec: Long?,
    val recentLastSuccessAt: Instant?,
    val backfillState: HistorySyncJobState,
    val backfillCompletedThroughSec: Long?,
    val backfillCursor: String?,
    val repairLastRunAt: Instant?,
    val lastError: String?,
    val nextRetryAt: Instant?,
)

data class HistorySyncStatus(
    val accountIdentityDigest: String,
    val feed: HistoryFeed,
    val scanId: String,
    val state: HistorySyncState,
    val windowStart: Instant?,
    val windowEnd: Instant?,
    val nextCursor: String?,
    val fetchedCount: Long,
    val storedCount: Long,
    val knownTotal: Long?,
    val lastPageAt: Instant?,
    val lastError: String?,
)

data class HistoryPageRequest(
    val accountIdentityDigest: String,
    val feed: HistoryFeed,
    val scanId: String,
    val startSec: Long,
    val endSec: Long,
    val requestedCursor: String?,
    val committedAt: Instant,
    val purpose: HistorySyncPurpose = HistorySyncPurpose.RECENT,
)

data class HistorySyncWindow(
    val accountIdentityDigest: String,
    val feed: HistoryFeed,
    val scanId: String,
    val startSec: Long,
    val endSec: Long,
    val cursor: String? = null,
    val purpose: HistorySyncPurpose = HistorySyncPurpose.RECENT,
)

const val HISTORY_MAX_SYNC_PAGES = 5_000

data class HistoryLedgerCoverageWindow(val startSec: Long, val endSec: Long) {
    init {
        require(startSec <= endSec)
    }
}

fun ExchangeTradeHistoryEntry.toHistoryTrade(accountIdentityDigest: String): HistoryTrade = HistoryTrade(
    accountIdentityDigest = accountIdentityDigest,
    tradeId = tradeId,
    orderTxid = orderTxid,
    postTxid = postTxid,
    pair = pair,
    baseAsset = baseAsset,
    quoteCurrency = quoteCurrency,
    time = time,
    rawTime = rawTime,
    type = type,
    orderType = orderType,
    price = price,
    cost = cost,
    fee = fee,
    volume = volume,
    margin = margin,
    misc = misc,
    tradeSequenceId = tradeSequenceId,
    maker = maker,
    ledgerIds = ledgerIds.orEmpty(),
    rawRecordJson = rawRecordJson,
    hasValidTime = hasValidTime,
    hasValidPrice = hasValidPrice,
    hasValidCost = hasValidCost,
    hasValidFee = hasValidFee,
    hasValidVolume = hasValidVolume,
    isValidRecord = isValidRecord,
)

fun ExchangeLedgerHistoryEntry.toHistoryLedger(accountIdentityDigest: String): HistoryLedger = HistoryLedger(
    accountIdentityDigest = accountIdentityDigest,
    ledgerId = ledgerId,
    refid = refid,
    time = time,
    rawTime = rawTime,
    type = type,
    subtype = subtype,
    assetClass = assetClass,
    asset = asset,
    amount = amount,
    fee = fee,
    balance = balance,
    rawRecordJson = rawRecordJson,
    hasValidTime = hasValidTime,
    hasValidAmount = hasValidAmount,
    hasValidFee = hasValidFee,
    hasValidBalance = hasValidBalance,
    isValidRecord = isValidRecord,
)
