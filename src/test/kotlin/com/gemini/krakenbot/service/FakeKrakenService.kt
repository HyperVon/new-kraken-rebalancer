package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import java.math.BigDecimal

/**
 * Test-only controllable [KrakenService]: suppliers drive balances/prices/history, and
 * [executedOrders] records placements. Prefer this in unit/evaluation tests.
 *
 * Distinct from [com.gemini.krakenbot.service.impl.SimulatedKrakenService], the production
 * emulator used when `settings.simulation=true` (seeded portfolio, drifted prices).
 *
 * Suppliers and [executeOrderAction] / [orderResultFactory] may be reassigned between tests.
 */
class FakeKrakenService : KrakenService {
    var balanceSupplier: () -> Map<String, Any> = { emptyMap() }
    var pricesSupplier: (String) -> Map<String, Any> = { emptyMap() }
    var tradeHistorySupplier: (Long?, Int?) -> List<TradeRecord> = { _, _ -> emptyList() }

    /** Optional side effect after recording (e.g. throw to simulate placement failure). */
    var executeOrderAction: ((String, String, String, BigDecimal) -> Unit)? =
        null

    /** When set, overrides the default successful [OrderResult]. */
    var orderResultFactory: ((String, String, String, BigDecimal) -> OrderResult)? =
        null

    var executedOrders = mutableListOf<OrderCall>()
    var getBalancesCallCount = 0
    var getTradeHistoryCallCount = 0
    var tradeHistoryTotalCountOverride = 0

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

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> {
        getTradeHistoryCallCount++
        return tradeHistorySupplier(startSec, offset)
    }

    override fun getLastTradeHistoryTotalCount(): Int = tradeHistoryTotalCountOverride

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal,
        dryRun: Boolean?,
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
                dryRun = dryRun == true,
                orderTxid = if (dryRun == true) null else "FAKE-ORDER-${executedOrders.size}",
            )
    }

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> = emptyList()
}

data class OrderCall(
    val pair: String,
    val type: String,
    val side: String,
    val volume: BigDecimal,
    val dryRun: Boolean? = null,
    val clOrdId: String? = null,
)
