package com.gemini.krakenbot.service

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.InternalTransferRecord
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.WithdrawStatusRecord
import java.math.BigDecimal

interface KrakenService {
    suspend fun getBalances(): RawBalances

    suspend fun getTickerPrices(pairs: String): RawPrices

    /**
     * @param dryRun is required and captured by the caller for this order so a mid-cycle settings
     * flip cannot change dry-run vs live placement.
     * @param clOrdId optional Kraken `cl_ord_id` (client order id). When set, Kraken enforces
     * uniqueness among the client's *open* orders — mutually exclusive with `userref`.
     */
    suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean,
        clOrdId: String? = null,
    ): OrderResult

    suspend fun getTradeHistory(startSec: Long? = null, offset: Int? = null): List<TradeRecord>

    suspend fun getOHLC(pair: String, interval: Int = 1440, since: Long? = null): List<Pair<Long, BigDecimal>>

    /**
     * Total trade count from the last [getTradeHistory] response (Kraken `count` / sim total).
     * Used for sync progress pagination metadata without downcasting the port.
     */
    fun getLastTradeHistoryTotalCount(): Int = 0

    /**
     * Ledger entries in [startSec, endSec] starting at [offset], optionally filtered to response
     * [types] (for example `staking`, `spend`, or `receive`). The live Kraken adapter maps the
     * consumer `spend`/`receive` response types to the API's `sale` query filter and filters the
     * returned rows locally. Defaults to empty for backends without ledger support (simulation,
     * test fakes).
     */
    suspend fun getLedgers(
        startSec: Long? = null,
        offset: Int? = null,
        endSec: Long? = null,
        types: Set<String>? = null,
    ): List<LedgerEvent> = emptyList()

    /**
     * Batch funding evidence from Kraken's authenticated DepositStatus
     * endpoint. Backends without a funding-status surface return an empty
     * list; callers must treat absence as unresolved provenance.
     */
    suspend fun getDepositStatus(startSec: Long? = null, endSec: Long? = null): List<DepositStatusRecord> = emptyList()

    /**
     * Batch funding evidence from Kraken's authenticated WithdrawStatus
     * endpoint. Backends without a funding-status surface return an empty
     * list; callers must treat absence as unresolved provenance.
     */
    suspend fun getWithdrawStatus(startSec: Long? = null, endSec: Long? = null): List<WithdrawStatusRecord> =
        emptyList()

    /**
     * Optional authoritative wallet-transfer evidence. Kraken's Spot REST
     * surface does not expose a historical Futures-transfer query, so the live
     * adapter returns empty until such a source is available.
     */
    suspend fun getInternalTransfers(startSec: Long? = null, endSec: Long? = null): List<InternalTransferRecord> =
        emptyList()

    /**
     * Stable account/configuration scope for cached funding evidence. The
     * value must not contain credentials; it only prevents evidence fetched
     * for one account or credential generation from being reused for another.
     */
    suspend fun getFundingEvidenceScope(): String = this::class.java.name

    /** Total ledger entry count from the last [getLedgers] response (Kraken `count`). */
    fun getLastLedgerTotalCount(): Int = 0

    /** Number of raw entries returned in the last [getLedgers] page before local type filtering. */
    fun getLastLedgerRawPageSize(): Int = 0

    /** Current private-API call-counter load; 0 for backends without a rate limiter. */
    suspend fun getApiCallCounter(): Double = 0.0

    /**
     * Runs [block] with a stable backend selection passed as the argument.
     * Default passes `this`. `DynamicKrakenService` pins live vs simulation in
     * the coroutine context at top-level entry; nested calls reuse the outer pin
     * so a mid-cycle `simulation` flip cannot mix backends. Concurrent top-level
     * calls each capture their own entry-time backend.
     */
    suspend fun <T> withStableBackend(block: suspend (KrakenService) -> T): T = block(this)
}

/** Optional capability for account balances reduced to amounts currently available for trading. */
interface SpendableBalanceService {
    suspend fun getSpendableBalances(): RawBalances
}

/** Optional capability for backends that can pass an inclusive TradesHistory end bound. */
interface BoundedTradeHistoryService {
    suspend fun getTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord>
}

/** Optional capability for backends that retrieve trade history without allocation filtering (for inception recovery). */
interface RecoveryTradeHistoryService {
    suspend fun getRecoveryTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord>
}

/** Uses the stable-bound capability when available and preserves two-argument test fakes otherwise. */
suspend fun KrakenService.getTradeHistoryUntil(startSec: Long?, offset: Int?, endSec: Long?): List<TradeRecord> =
    (this as? BoundedTradeHistoryService)?.getTradeHistoryUntil(startSec, offset, endSec)
        ?: getTradeHistory(startSec, offset)

/** Retrieves recovery trade history preserving unmapped pairs, falling back to bounded or basic history. */
suspend fun KrakenService.getRecoveryTradeHistoryUntil(
    startSec: Long?,
    offset: Int?,
    endSec: Long?,
): List<TradeRecord> = (this as? RecoveryTradeHistoryService)?.getRecoveryTradeHistoryUntil(startSec, offset, endSec)
    ?: getTradeHistoryUntil(startSec, offset, endSec)
