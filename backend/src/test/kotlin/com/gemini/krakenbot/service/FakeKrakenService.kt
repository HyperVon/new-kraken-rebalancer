package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.RawBalances
import com.gemini.krakenbot.domain.RawPrices
import com.gemini.krakenbot.model.ExchangeTradePage
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.KrakenAssetMetadata
import java.math.BigDecimal

/**
 * Test-only controllable [KrakenService]: suppliers drive balances/prices, and
 * [executedOrders] records placements. Prefer this in unit/evaluation tests.
 *
 * Distinct from [com.gemini.krakenbot.service.impl.SimulatedKrakenService], the production
 * emulator used when `settings.simulation=true` (seeded portfolio, drifted prices).
 *
 * Suppliers and [executeOrderAction] / [orderResultFactory] may be reassigned between tests.
 * [tradePageSupplier] serves recent fill cursor pages; the default is one empty valid page.
 */
class FakeKrakenService : KrakenService {
    var balanceSupplier: () -> Map<String, Any> = { emptyMap() }
    var pricesSupplier: (String) -> Map<String, Any> = { emptyMap() }
    var assetMetadataSupplier: () -> List<KrakenAssetMetadata> = { DEFAULT_ASSET_METADATA }
    var tradePageSupplier: (Long?, Long?, String?, Int?) -> ExchangeTradePage =
        { _, _, _, _ -> emptyPage() }
    var fundingEvidenceScopeSupplier: () -> String = { "fake-account" }
    var authenticatedAccountIdentitySupplier: () -> String? = { "AA00 TEST TEST TEST" }

    /** Optional side effect after recording (e.g. throw to simulate placement failure). */
    var executeOrderAction: ((String, String, String, BigDecimal) -> Unit)? =
        null

    /** When set, overrides the default successful [OrderResult]. */
    var orderResultFactory: ((String, String, String, BigDecimal) -> OrderResult)? =
        null

    var executedOrders = mutableListOf<OrderCall>()
    var getBalancesCallCount = 0
    var getTradePageCallCount = 0
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

    override suspend fun getRecentTradePage(
        startSec: Long?,
        endSec: Long?,
        cursor: String?,
        limit: Int?,
    ): ExchangeTradePage {
        getTradePageCallCount++
        return tradePageSupplier(startSec, endSec, cursor, limit)
    }

    override suspend fun getFundingEvidenceScope(): String = fundingEvidenceScopeSupplier()

    override suspend fun getAuthenticatedAccountIdentity(): String? {
        getAuthenticatedAccountIdentityCallCount++
        return authenticatedAccountIdentitySupplier()
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

    private companion object {
        fun emptyPage() = ExchangeTradePage(
            entries = emptyList(),
            nextCursor = null,
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
                TestFixtures.A,
                TestFixtures.B,
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
