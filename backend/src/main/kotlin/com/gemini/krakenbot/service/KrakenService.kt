package com.gemini.krakenbot.service

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import com.gemini.krakenbot.model.KrakenAssetMetadata
import java.math.BigDecimal

interface KrakenService {
    suspend fun getBalances(): RawBalances

    suspend fun getTickerPrices(pairs: String): RawPrices

    /** Returns exchange-reported asset IDs and classes; backends without metadata return none. */
    suspend fun getAssetMetadata(): List<KrakenAssetMetadata> = emptyList()

    /** Returns current exchange metadata without stale-cache fallback for trading eligibility. */
    suspend fun getFreshAssetMetadata(): List<KrakenAssetMetadata> = getAssetMetadata()

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

    /** Returns one raw account-wide TradesHistory cursor page. */
    suspend fun getAccountTradePage(
        startSec: Long? = null,
        endSec: Long? = null,
        cursor: String? = null,
        limit: Int? = null,
    ): ExchangeHistoryPage<ExchangeTradeHistoryEntry>

    /** Returns one raw account-wide Ledgers cursor page, retaining Kraken asset codes verbatim. */
    suspend fun getAccountLedgerPage(
        startSec: Long? = null,
        endSec: Long? = null,
        cursor: String? = null,
    ): ExchangeHistoryPage<ExchangeLedgerHistoryEntry>

    suspend fun getOHLC(pair: String, interval: Int = 1440, since: Long? = null): List<Pair<Long, BigDecimal>>

    /**
     * Stable account/configuration scope for cached funding evidence. The
     * value must not contain credentials; it only prevents evidence fetched
     * for one account or credential generation from being reused for another.
     */
    suspend fun getFundingEvidenceScope(): String = this::class.java.name

    /** Authenticated stable account identity (Kraken Spot IIBAN); never use a credential hash here. */
    suspend fun getAuthenticatedAccountIdentity(): String? = null

    /** Authenticated API-key account identity and verified private-history time restrictions. */
    suspend fun getAuthenticatedAccountInfo(): AuthenticatedAccountInfo? = getAuthenticatedAccountIdentity()?.let {
        AuthenticatedAccountInfo(identity = it, queryBoundsVerified = false)
    }

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

data class AuthenticatedAccountInfo(
    val identity: String,
    /** Inclusive earliest allowed query second; null means Kraken reports no lower restriction. */
    val queryFromSec: Long? = null,
    /** Inclusive latest allowed query second; null means Kraken reports no upper restriction. */
    val queryToSec: Long? = null,
    val queryBoundsVerified: Boolean,
) {
    init {
        require(identity.isNotBlank())
        require(queryFromSec == null || queryFromSec >= 0)
        require(queryToSec == null || queryToSec >= 0)
        require(queryFromSec == null || queryToSec == null || queryFromSec <= queryToSec)
    }
}

/** Optional capability for account balances reduced to amounts currently available for trading. */
interface SpendableBalanceService {
    suspend fun getSpendableBalances(): RawBalances
}

/** Optional direct-response evidence surface implemented by live Kraken adapters, not simulations. */
interface DirectKrakenEvidenceService {
    suspend fun readBalancesWithCapture(): BalanceRead

    suspend fun readTickerPricesWithCapture(requests: List<DirectTickerRequest>): TickerRead
}

/** Simulation and test backends preserve legacy values without claiming authenticated evidence. */
suspend fun KrakenService.readBalancesWithCapture(): BalanceRead =
    (this as? DirectKrakenEvidenceService)?.readBalancesWithCapture() ?: BalanceRead(getBalances())

/** Simulation and test backends preserve legacy values without claiming authenticated evidence. */
suspend fun KrakenService.readTickerPricesWithCapture(requests: List<DirectTickerRequest>): TickerRead =
    if (requests.isEmpty()) {
        TickerRead(emptyMap())
    } else {
        (this as? DirectKrakenEvidenceService)?.readTickerPricesWithCapture(requests)
            ?: TickerRead(getTickerPrices(requests.joinToString(",") { it.pair }))
    }
