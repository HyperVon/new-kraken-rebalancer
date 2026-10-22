package com.gemini.krakenbot.service

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.KrakenAssetMetadata
import java.math.BigDecimal

/**
 * Test-only controllable [KrakenService]: suppliers drive balances/prices/history, and
 * [executedOrders] records placements. Prefer this in unit/evaluation tests.
 *
 * Distinct from [com.gemini.krakenbot.service.impl.SimulatedKrakenService], the production
 * emulator used when `settings.simulation=true` (seeded portfolio, drifted prices).
 *
 * Suppliers and [executeOrderAction] / [orderResultFactory] may be reassigned between tests.
 * [tradePageSupplier] and [ledgerPageSupplier] serve raw cursor pages; the default is one empty valid page.
 */
class FakeKrakenService : KrakenService {
    var balanceSupplier: () -> Map<String, Any> = { emptyMap() }
    var pricesSupplier: (String) -> Map<String, Any> = { emptyMap() }
    var assetMetadataSupplier: () -> List<KrakenAssetMetadata> = { DEFAULT_ASSET_METADATA }
    var tradePageSupplier: (Long?, Long?, String?, Int?) -> ExchangeHistoryPage<ExchangeTradeHistoryEntry> =
        { startSec, endSec, cursor, _ -> emptyPage(startSec, endSec, cursor) }
    var ledgerPageSupplier: (Long?, Long?, String?) -> ExchangeHistoryPage<ExchangeLedgerHistoryEntry> =
        { startSec, endSec, cursor -> emptyPage(startSec, endSec, cursor) }
    var ohlcSupplier: (String, Int, Long?) -> List<Pair<Long, BigDecimal>> = { _, _, _ -> emptyList() }
    var fundingEvidenceScopeSupplier: () -> String = { "fake-account" }
    var authenticatedAccountIdentitySupplier: () -> String? = { "AA00 TEST TEST TEST" }
    var authenticatedAccountInfoFactory: (String) -> AuthenticatedAccountInfo = { identity ->
        AuthenticatedAccountInfo(identity, queryBoundsVerified = true)
    }

    /** Optional side effect after recording (e.g. throw to simulate placement failure). */
    var executeOrderAction: ((String, String, String, BigDecimal) -> Unit)? =
        null

    /** When set, overrides the default successful [OrderResult]. */
    var orderResultFactory: ((String, String, String, BigDecimal) -> OrderResult)? =
        null

    var executedOrders = mutableListOf<OrderCall>()
    var getBalancesCallCount = 0
    var getTradePageCallCount = 0
    var getLedgerPageCallCount = 0
    var getOHLCCallCount = 0
    var getAuthenticatedAccountIdentityCallCount = 0

    override suspend fun getBalances(): RawBalances {
        getBalancesCallCount++
        return balanceSupplier().mapValues { (_, value) ->
            when (value) {
                is BigDecimal -> value
                is Double -> BigDecimal.valueOf(value)
                is Number -> BigDecimal(value.toString())
                else -> BigDecimal.ZERO
            }
        }
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices = pricesSupplier(pairs).mapValues { (_, value) ->
        when (value) {
            is BigDecimal -> value
            is Double -> BigDecimal.valueOf(value)
            is Number -> BigDecimal(value.toString())
            else -> BigDecimal.ZERO
        }
    }

    var getAssetMetadataCallCount = 0
        private set

    override suspend fun getAssetMetadata(): List<KrakenAssetMetadata> {
        getAssetMetadataCallCount++
        return assetMetadataSupplier()
    }

    override suspend fun getAccountTradePage(
        startSec: Long?,
        endSec: Long?,
        cursor: String?,
        limit: Int?,
    ): ExchangeHistoryPage<ExchangeTradeHistoryEntry> {
        getTradePageCallCount++
        return tradePageSupplier(startSec, endSec, cursor, limit)
    }

    override suspend fun getAccountLedgerPage(
        startSec: Long?,
        endSec: Long?,
        cursor: String?,
    ): ExchangeHistoryPage<ExchangeLedgerHistoryEntry> {
        getLedgerPageCallCount++
        return ledgerPageSupplier(startSec, endSec, cursor)
    }

    override suspend fun getFundingEvidenceScope(): String = fundingEvidenceScopeSupplier()

    override suspend fun getAuthenticatedAccountIdentity(): String? {
        getAuthenticatedAccountIdentityCallCount++
        return authenticatedAccountIdentitySupplier()
    }

    override suspend fun getAuthenticatedAccountInfo(): AuthenticatedAccountInfo? =
        authenticatedAccountIdentitySupplier()?.let { identity ->
            getAuthenticatedAccountIdentityCallCount++
            authenticatedAccountInfoFactory(identity)
        }

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean,
        clOrdId: String?,
    ): OrderResult {
        executedOrders.add(OrderCall(pair, type, side, volume, dryRun, clOrdId))
        executeOrderAction?.invoke(pair, type, side, volume)
        return orderResultFactory?.invoke(pair, type, side, volume)
            ?: OrderResult(
                success = true,
                pair = pair,
                side = side,
                volume = volume,
                dryRun = dryRun,
                orderTxid = if (dryRun) null else "FAKE-ORDER-${executedOrders.size}",
            )
    }

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> {
        getOHLCCallCount++
        return ohlcSupplier(pair, interval, since)
    }

    private companion object {
        fun <T> emptyPage(startSec: Long?, endSec: Long?, cursor: String?) = ExchangeHistoryPage<T>(
            entries = emptyList(),
            startSec = startSec,
            endSec = endSec,
            requestedCursor = cursor,
            nextCursor = null,
            count = null,
            rawSize = 0,
            valid = true,
        )

        val DEFAULT_ASSET_METADATA =
            listOf(
                "USD",
                "USDG",
                "BTC",
                "ETH",
                "ADA",
                "MORPHO",
                "XMR",
                "ATOM",
                "DOGE",
                "SOL",
                "XRP",
                "XLM",
                "DOT",
                "LINK",
                "LTC",
                "USDT",
                "USDC",
                "BABY",
            ).map { KrakenAssetMetadata(it, KrakenApiConstants.ASSET_CLASS_CURRENCY) } +
                KrakenAssetMetadata("TOKENIZED_ASSET_1", KrakenApiConstants.ASSET_CLASS_TOKENIZED_ASSET)
    }
}

data class OrderCall(
    val pair: String,
    val type: String,
    val side: String,
    val volume: BigDecimal,
    val dryRun: Boolean,
    val clOrdId: String? = null,
)
